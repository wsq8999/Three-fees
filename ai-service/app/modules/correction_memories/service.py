from __future__ import annotations

"""人工纠错解析、确认、审计和查询服务。"""

import json
from datetime import datetime, timezone
from decimal import Decimal
from uuid import UUID, uuid4

from pydantic import ValidationError
from sqlalchemy.orm import Session

from app.agents.audit.prompt_loader import load_prompt
from app.core.config import get_settings
from app.core.exceptions import AppError, ConflictError, ResourceNotFoundError
from app.core.identity import CurrentUser
from app.integrations.ai.base import AIProviderError
from app.integrations.ai.factory import get_ai_provider
from app.modules.analysis_runs.repository import AnalysisRunRepository
from app.modules.audit_logs.repository import AuditLogRepository
from app.modules.audit_tasks.repository import AuditTaskRepository
from app.modules.cities.schemas import CityContext
from app.modules.correction_memories.model import CorrectionMemoryModel
from app.modules.correction_memories.repository import CorrectionMemoryRepository
from app.modules.correction_memories.schemas import (
    CorrectionInterpretation,
    CorrectionMemoryUpdate,
    CorrectionMemoryView,
)
from app.modules.sites.model import SiteModel

PROMPT_VERSION = "interpret_correction_v1"


def _to_view(
    memory: CorrectionMemoryModel, site: SiteModel, city_code: str
) -> CorrectionMemoryView:
    """生成不暴露数据库内部对象的REST响应。"""
    return CorrectionMemoryView(
        id=str(memory.id),
        city_code=city_code,
        site_id=str(memory.site_id),
        site_name=site.site_name,
        task_id=str(memory.task_id),
        analysis_run_id=str(memory.analysis_run_id),
        status=memory.status,
        memory_status=memory.memory_status,
        original_reason=memory.original_reason,
        original_reason_category=memory.original_reason_category,
        user_message=memory.user_message,
        corrected_reason=memory.corrected_reason,
        reason_category=memory.reason_category,
        applicability_conditions=memory.applicability_conditions,
        supporting_element_ids=memory.supporting_element_ids,
        interpretation_summary=memory.interpretation_summary,
        uncertain_items=memory.uncertain_items,
        confidence=float(memory.confidence),
        model_name=memory.model_name,
        prompt_version=memory.prompt_version,
        created_by=str(memory.created_by),
        confirmed_by=str(memory.confirmed_by) if memory.confirmed_by else None,
        version=memory.version,
        created_at=memory.created_at,
        updated_at=memory.updated_at,
        confirmed_at=memory.confirmed_at,
    )


class CorrectionMemoryService:
    """把自然语言纠错变成“先确认、后学习”的城市独立记忆。"""

    def __init__(self, session: Session) -> None:
        self.session = session
        self.repository = CorrectionMemoryRepository(session)

    def create_draft(
        self,
        *,
        run_id: str,
        message: str,
        city: CityContext,
        user: CurrentUser,
    ) -> CorrectionMemoryView:
        """解析一次已完成运行的纠错原话，并保存为不能参与RAG的draft。

        一次运行只保留一条纠错资源。已有draft/rejected时允许用新原话重新解释；已有
        confirmed记忆时拒绝覆盖，避免未经明确归档就改写已经影响后续判断的知识。
        """
        run = AnalysisRunRepository(self.session).get(run_id, city.id)
        if run is None:
            raise ResourceNotFoundError("分析运行不存在")
        if run.status != "completed" or not isinstance(run.result, dict):
            raise ConflictError("只有已经成功完成原因判断的运行才能提交纠错")
        task_row = AuditTaskRepository(self.session).get(run.task_id, city.id)
        if task_row is None:
            raise ResourceNotFoundError("纠错来源任务不存在")
        task, site = task_row

        existing = self.repository.get_by_run(run.id, city.id)
        if existing is not None and existing.status in {"confirmed", "archived"}:
            raise ConflictError("该分析运行已经形成确认记忆，不能直接覆盖")

        judgment = run.result.get("judgment")
        facts = run.result.get("facts")
        current_materials = run.result.get("current_materials")
        if not isinstance(judgment, dict) or not isinstance(facts, dict):
            raise ConflictError("该运行缺少可纠错的原因判断或本次报告事实")

        interpretation = self._interpret(message, judgment, facts, current_materials)
        allowed_element_ids = self._current_element_ids(current_materials)
        if not set(interpretation.supporting_current_element_ids) <= allowed_element_ids:
            raise AIProviderError(
                "correction_evidence_invalid", "纠错理解引用了不属于本次报告的元素ID"
            )

        now = datetime.now(timezone.utc)
        if existing is None:
            memory = CorrectionMemoryModel(
                id=uuid4(),
                city_id=city.id,
                site_id=task.site_id,
                task_id=task.id,
                analysis_run_id=run.id,
                status="draft",
                memory_status="paused",
                original_reason=judgment.get("primary_reason"),
                original_reason_category=judgment.get("reason_category"),
                original_judgment=judgment,
                user_message=message.strip(),
                corrected_reason=interpretation.corrected_reason,
                reason_category=interpretation.reason_category,
                applicability_conditions=interpretation.applicability_conditions,
                supporting_element_ids=interpretation.supporting_current_element_ids,
                interpretation_summary=interpretation.interpretation_summary,
                uncertain_items=interpretation.uncertain_items,
                confidence=Decimal(str(interpretation.confidence)),
                model_name=get_ai_provider().model_name,
                prompt_version=PROMPT_VERSION,
                created_by=UUID(user.id),
                updated_at=now,
            )
            self.repository.add(memory)
            action = "correction_memory.draft_created"
        else:
            # rejected允许重新提交，因为它从未参与过RAG；来源运行和原判断保持不变。
            memory = existing
            memory.status = "draft"
            memory.memory_status = "paused"
            memory.user_message = message.strip()
            memory.corrected_reason = interpretation.corrected_reason
            memory.reason_category = interpretation.reason_category
            memory.applicability_conditions = interpretation.applicability_conditions
            memory.supporting_element_ids = interpretation.supporting_current_element_ids
            memory.interpretation_summary = interpretation.interpretation_summary
            memory.uncertain_items = interpretation.uncertain_items
            memory.confidence = Decimal(str(interpretation.confidence))
            memory.model_name = get_ai_provider().model_name
            memory.prompt_version = PROMPT_VERSION
            memory.confirmed_by = None
            memory.confirmed_at = None
            memory.version += 1
            memory.updated_at = now
            action = "correction_memory.draft_rebuilt"

        AuditLogRepository(self.session).append(
            city_id=city.id,
            user_id=UUID(user.id),
            action=action,
            entity_type="correction_memory",
            entity_id=str(memory.id),
            after_data={"status": "draft", "analysis_run_id": str(run.id)},
        )
        self.session.commit()
        self.session.refresh(memory)
        return _to_view(memory, site, city.code)

    def update(
        self,
        *,
        memory_id: str,
        payload: CorrectionMemoryUpdate,
        city: CityContext,
        user: CurrentUser,
    ) -> CorrectionMemoryView:
        """确认或驳回草稿；确认时允许业务员修正AI整理后的标准表达。"""
        row = self.repository.get(memory_id, city.id)
        if row is None:
            raise ResourceNotFoundError("纠错记忆不存在")
        memory, site = row
        if memory.status == payload.status:
            # 页面重复点击或网络重试保持幂等，不重复增加版本和审计日志。
            return _to_view(memory, site, city.code)
        if memory.status != "draft":
            raise ConflictError("只有待确认的纠错草稿可以确认或驳回")

        before = {
            "status": memory.status,
            "corrected_reason": memory.corrected_reason,
            "reason_category": memory.reason_category,
        }
        if payload.corrected_reason is not None:
            memory.corrected_reason = payload.corrected_reason.strip()
        if payload.reason_category is not None:
            memory.reason_category = payload.reason_category.strip()
        if payload.applicability_conditions is not None:
            memory.applicability_conditions = payload.applicability_conditions
        memory.status = payload.status
        # 只有已确认纠错可以进入检索；驳回内容保留作审计，但不会参与后续判断。
        memory.memory_status = "active" if payload.status == "confirmed" else "paused"
        memory.version += 1
        memory.updated_at = datetime.now(timezone.utc)
        if payload.status == "confirmed":
            memory.confirmed_by = UUID(user.id)
            memory.confirmed_at = memory.updated_at

        AuditLogRepository(self.session).append(
            city_id=city.id,
            user_id=UUID(user.id),
            action=f"correction_memory.{payload.status}",
            entity_type="correction_memory",
            entity_id=str(memory.id),
            before_data=before,
            after_data={
                "status": memory.status,
                "corrected_reason": memory.corrected_reason,
                "reason_category": memory.reason_category,
            },
        )
        self.session.commit()
        self.session.refresh(memory)
        return _to_view(memory, site, city.code)

    def get(self, memory_id: str, city: CityContext) -> CorrectionMemoryView:
        """查看当前城市单条纠错的完整确认上下文。"""
        row = self.repository.get(memory_id, city.id)
        if row is None:
            raise ResourceNotFoundError("纠错记忆不存在")
        return _to_view(*row, city.code)

    def list(self, city: CityContext, *, site_id: str | None = None) -> list[CorrectionMemoryView]:
        """列出当前城市纠错，管理页面可同时看到draft、confirmed和rejected。"""
        try:
            normalized_site_id = UUID(site_id) if site_id else None
        except ValueError as exc:
            raise AppError(
                status=400,
                code="invalid_site_id",
                title="报账点标识无效",
                detail="site_id必须是有效UUID",
            ) from exc
        return [
            _to_view(memory, site, city.code)
            for memory, site in self.repository.list_by_city(city.id, site_id=normalized_site_id)
        ]

    def _interpret(
        self,
        message: str,
        judgment: dict,
        facts: dict,
        current_materials: object,
    ) -> CorrectionInterpretation:
        """让Kimi整理业务员原话，但不在这一阶段自动确认或写入RAG。"""
        user_content = [
            {
                "type": "text",
                "text": (
                    "AI原判断：\n"
                    + json.dumps(judgment, ensure_ascii=False)
                    + "\n本次报告结构化事实：\n"
                    + json.dumps(facts, ensure_ascii=False)
                    + "\n本次报告可引用元素：\n"
                    + json.dumps(current_materials, ensure_ascii=False)
                    + "\n业务员纠错原话：\n"
                    + message.strip()
                ),
            }
        ]
        raw = get_ai_provider().generate_structured(
            system_prompt=load_prompt("interpret_correction_v1.md"),
            user_content=user_content,
            schema_name="electricity_audit_correction_interpretation",
            json_schema=CorrectionInterpretation.model_json_schema(),
            reasoning_effort=get_settings().kimi_judge_reasoning_effort,
        )
        try:
            return CorrectionInterpretation.model_validate(raw)
        except ValidationError as exc:
            raise AIProviderError("ai_output_schema_invalid", "Kimi纠错理解结果字段不合规") from exc

    @staticmethod
    def _current_element_ids(current_materials: object) -> set[int]:
        """从冻结运行快照提取合法本次元素ID，供模型引用白名单校验。"""
        if not isinstance(current_materials, list):
            return set()
        return {
            int(element["element_id"])
            for material in current_materials
            if isinstance(material, dict) and isinstance(material.get("elements"), list)
            for element in material["elements"]
            if isinstance(element, dict) and isinstance(element.get("element_id"), int)
        }
