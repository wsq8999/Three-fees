from __future__ import annotations

"""统一记忆列表、暂停、标错、复核和修订服务。"""

from datetime import datetime, timezone
from uuid import UUID, uuid4

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.exceptions import AppError, ConflictError, ResourceNotFoundError
from app.core.identity import CurrentUser
from app.modules.analysis_runs.repository import AnalysisRunRepository
from app.modules.audit_cases.model import AuditCaseModel
from app.modules.audit_logs.repository import AuditLogRepository
from app.modules.cities.schemas import CityContext
from app.modules.correction_memories.model import CorrectionMemoryModel
from app.modules.documents.model import DocumentElementModel, SourceDocumentModel
from app.modules.memory_governance.model import MemoryFlagModel, MemoryRevisionModel
from app.modules.memory_governance.repository import MemoryGovernanceRepository, parse_uuid
from app.modules.memory_governance.schemas import (
    MemoryFlagCreate,
    MemoryFlagResolve,
    MemoryFlagView,
    MemoryRevisionCreate,
    MemoryRevisionView,
    MemoryStatusUpdate,
    MemoryType,
    MemoryView,
)
from app.modules.sites.model import SiteModel


class MemoryGovernanceService:
    """为两种来源提供一套简单、一致且可审计的记忆操作。"""

    def __init__(self, session: Session) -> None:
        self.session = session
        self.repository = MemoryGovernanceRepository(session)

    def list(
        self,
        city: CityContext,
        *,
        memory_type: MemoryType | None = None,
        memory_status: str | None = None,
        site_id: str | None = None,
        page: int = 1,
        page_size: int = 50,
    ) -> tuple[list[MemoryView], int]:
        """分页列出当前城市长期记忆，并支持页面常用筛选。"""
        normalized_site_id = parse_uuid(site_id) if site_id else None
        if site_id and normalized_site_id is None:
            raise AppError(
                status=400,
                code="invalid_site_id",
                title="报账点标识无效",
                detail="site_id必须是有效UUID",
            )

        # 两种来源分表保存。各取到当前页末端，再统一按更新时间合并切片，既保持全局
        # 顺序，也避免每次打开页面把一个城市未来可能积累的全部记忆载入应用内存。
        candidate_limit = page * page_size
        raw_items: list[tuple[str, tuple]] = []
        total = 0
        if memory_type in {None, "audit_case"}:
            total += self.repository.count_audit_cases(city.id, normalized_site_id, memory_status)
            raw_items.extend(
                ("audit_case", row)
                for row in self.repository.list_audit_cases(
                    city.id, normalized_site_id, memory_status, candidate_limit
                )
            )
        if memory_type in {None, "correction_memory"}:
            total += self.repository.count_corrections(city.id, normalized_site_id, memory_status)
            raw_items.extend(
                ("correction_memory", row)
                for row in self.repository.list_corrections(
                    city.id, normalized_site_id, memory_status, candidate_limit
                )
            )
        raw_items.sort(key=lambda item: item[1][0].updated_at, reverse=True)
        start = (page - 1) * page_size
        page_rows = raw_items[start : start + page_size]

        audit_ids = [row[1][0].id for row in page_rows if row[0] == "audit_case"]
        correction_ids = [row[1][0].id for row in page_rows if row[0] == "correction_memory"]
        open_flags = self.repository.list_open_flags(city.id, audit_ids, correction_ids)
        flag_ids = {
            flag.audit_case_id or flag.correction_memory_id: str(flag.id) for flag in open_flags
        }
        items = [
            self._audit_view(*row, city.code, flag_ids.get(row[0].id))
            if memory_kind == "audit_case"
            else self._correction_view(*row, city.code, flag_ids.get(row[0].id))
            for memory_kind, row in page_rows
        ]
        return items, total

    def update_status(
        self,
        memory_type: MemoryType,
        memory_id: str,
        payload: MemoryStatusUpdate,
        city: CityContext,
        user: CurrentUser,
    ) -> MemoryView:
        """暂停或恢复一条正常记忆；已确认错误的版本只能通过修订恢复。"""
        target, view_args = self._target(memory_type, memory_id, city)
        if target.memory_status == "invalidated":
            raise ConflictError("已标错记忆必须修改内容并生成新版本后才能重新启用")
        if self.repository.get_open_flag(memory_type, target.id, city.id) is not None:
            raise ConflictError("该记忆正在等待标错复核，不能绕过复核流程修改状态")
        if target.memory_status == payload.memory_status:
            return self._view(memory_type, target, view_args, city.code)
        if payload.memory_status == "active" and not self._process_allows_active(
            memory_type, target
        ):
            raise ConflictError("尚未完成生成或确认流程的记忆不能启用")

        before = target.memory_status
        target.memory_status = payload.memory_status
        target.updated_at = datetime.now(timezone.utc)
        self._audit(
            city.id,
            user,
            f"memory.{payload.memory_status}",
            memory_type,
            target.id,
            {"memory_status": before},
            {"memory_status": target.memory_status},
        )
        self.session.commit()
        return self._view(memory_type, target, view_args, city.code)

    def create_flag(
        self,
        memory_type: MemoryType,
        memory_id: str,
        payload: MemoryFlagCreate,
        city: CityContext,
        user: CurrentUser,
    ) -> MemoryFlagView:
        """标错并立即隔离目标，避免等待复核期间继续污染后续判断。"""
        target, _ = self._target(memory_type, memory_id, city)
        if target.memory_status == "invalidated":
            raise ConflictError("该记忆已经确认标错，无需重复标记")
        existing = self.repository.get_open_flag(memory_type, target.id, city.id)
        if existing is not None:
            raise ConflictError("该记忆已有待处理标错")

        flag = MemoryFlagModel(
            id=uuid4(),
            city_id=city.id,
            audit_case_id=target.id if memory_type == "audit_case" else None,
            correction_memory_id=target.id if memory_type == "correction_memory" else None,
            flag_type=payload.flag_type,
            description=payload.description.strip(),
            previous_memory_status=target.memory_status,
            reported_by=UUID(user.id),
        )
        self.session.add(flag)
        # open标错统一使用paused，而不是直接invalidated，给误标恢复保留清晰路径。
        target.memory_status = "paused"
        target.updated_at = datetime.now(timezone.utc)
        self._audit(
            city.id,
            user,
            "memory.flagged",
            memory_type,
            target.id,
            {"memory_status": flag.previous_memory_status},
            {"memory_status": "paused", "flag_id": str(flag.id)},
        )
        self.session.commit()
        self.session.refresh(flag)
        return self._flag_view(flag, memory_type, city.code)

    def resolve_flag(
        self,
        flag_id: str,
        payload: MemoryFlagResolve,
        city: CityContext,
        user: CurrentUser,
    ) -> MemoryFlagView:
        """处理标错：误标恢复原状态，确认错误则将旧版本设为invalidated。"""
        flag = self.repository.get_flag(flag_id, city.id)
        if flag is None:
            raise ResourceNotFoundError("标错记录不存在")
        if flag.status != "open":
            raise ConflictError("该标错已经处理")
        memory_type: MemoryType = (
            "audit_case" if flag.audit_case_id is not None else "correction_memory"
        )
        memory_id = flag.audit_case_id or flag.correction_memory_id
        target, _ = self._target(memory_type, str(memory_id), city)
        before = target.memory_status
        now = datetime.now(timezone.utc)
        if payload.resolution == "dismissed":
            target.memory_status = flag.previous_memory_status
            flag.status = "dismissed"
            flag.resolution_action = "restored"
        else:
            target.memory_status = "invalidated"
            flag.status = "resolved"
            flag.resolution_action = "invalidated"
        target.updated_at = now
        flag.resolution_note = payload.note.strip() if payload.note else None
        flag.resolved_by = UUID(user.id)
        flag.resolved_at = now
        self._audit(
            city.id,
            user,
            f"memory.flag_{payload.resolution}",
            memory_type,
            target.id,
            {"memory_status": before, "flag_status": "open"},
            {"memory_status": target.memory_status, "flag_status": flag.status},
        )
        self.session.commit()
        self.session.refresh(flag)
        return self._flag_view(flag, memory_type, city.code)

    def revise(
        self,
        memory_type: MemoryType,
        memory_id: str,
        payload: MemoryRevisionCreate,
        city: CityContext,
        user: CurrentUser,
    ) -> MemoryRevisionView:
        """保存旧版本快照，修正内容，递增版本并重新启用。"""
        target, view_args = self._target(memory_type, memory_id, city)
        if target.memory_status != "invalidated":
            raise ConflictError("只有已确认标错的记忆才能通过此接口生成修正版")
        replaced_version = target.version
        revision = MemoryRevisionModel(
            id=uuid4(),
            city_id=city.id,
            audit_case_id=target.id if memory_type == "audit_case" else None,
            correction_memory_id=target.id if memory_type == "correction_memory" else None,
            revision_no=replaced_version,
            snapshot=self._snapshot(memory_type, target),
            change_reason=payload.change_reason.strip(),
            created_by=UUID(user.id),
        )
        self.session.add(revision)
        # 页面可以保留旧证据ID或提交人工调整后的ID；任何新ID都必须再次通过来源白名单。
        if payload.evidence_element_ids is not None:
            self._validate_evidence(memory_type, target, payload.evidence_element_ids)
        if memory_type == "audit_case":
            target.primary_reason = payload.reason.strip()
            target.reason_category = payload.reason_category.strip()
            target.key_facts = payload.conditions
            if payload.evidence_element_ids is not None:
                target.evidence_element_ids = payload.evidence_element_ids
        else:
            target.corrected_reason = payload.reason.strip()
            target.reason_category = payload.reason_category.strip()
            target.applicability_conditions = payload.conditions
            if payload.evidence_element_ids is not None:
                target.supporting_element_ids = payload.evidence_element_ids
        target.memory_status = "active"
        target.version += 1
        target.updated_at = datetime.now(timezone.utc)
        self._audit(
            city.id,
            user,
            "memory.revised_and_activated",
            memory_type,
            target.id,
            revision.snapshot,
            {"memory_status": "active", "version": target.version},
        )
        self.session.commit()
        return MemoryRevisionView(
            memory=self._view(memory_type, target, view_args, city.code),
            replaced_version=replaced_version,
        )

    def _validate_evidence(
        self, memory_type: MemoryType, target, evidence_element_ids: list[int]
    ) -> None:
        """验证修订证据仍属于该记忆的原始城市与来源材料。"""
        requested = set(evidence_element_ids)
        if memory_type == "audit_case":
            allowed = set(
                self.session.scalars(
                    select(DocumentElementModel.id).where(
                        DocumentElementModel.city_id == target.city_id,
                        DocumentElementModel.document_id == target.source_document_id,
                    )
                ).all()
            )
        else:
            # 人工纠错只能引用其来源运行冻结的本次报告元素，不能借修订混入其他任务证据。
            run = AnalysisRunRepository(self.session).get(target.analysis_run_id, target.city_id)
            current_materials = run.result.get("current_materials", []) if run else []
            allowed = {
                int(element["element_id"])
                for material in current_materials
                if isinstance(material, dict) and isinstance(material.get("elements"), list)
                for element in material["elements"]
                if isinstance(element, dict) and isinstance(element.get("element_id"), int)
            }
        if not requested <= allowed:
            raise AppError(
                status=400,
                code="memory_evidence_invalid",
                title="记忆证据无效",
                detail="证据元素必须属于该记忆原始城市和来源材料",
            )

    def _target(self, memory_type: MemoryType, memory_id: str, city: CityContext):
        """取得多态目标；无论ID错误还是跨城市均返回统一404。"""
        if memory_type == "audit_case":
            row = self.repository.get_audit_case(memory_id, city.id)
            if row is None:
                raise ResourceNotFoundError("记忆不存在")
            case, document, site = row
            return case, (document, site)
        row = self.repository.get_correction(memory_id, city.id)
        if row is None:
            raise ResourceNotFoundError("记忆不存在")
        memory, site = row
        return memory, (site,)

    def _view(self, memory_type: MemoryType, target, args: tuple, city_code: str) -> MemoryView:
        """根据来源类型调用对应统一视图转换。"""
        if memory_type == "audit_case":
            return self._audit_view(target, args[0], args[1], city_code)
        return self._correction_view(target, args[0], city_code)

    def _audit_view(
        self,
        case: AuditCaseModel,
        document: SourceDocumentModel,
        site: SiteModel,
        city_code: str,
        open_flag_id: str | None = None,
    ) -> MemoryView:
        """将历史案例映射为统一记忆视图。"""
        return MemoryView(
            id=str(case.id),
            memory_type="audit_case",
            city_code=city_code,
            site_id=str(case.site_id),
            site_name=site.site_name,
            source_label=document.title,
            process_status=case.status,
            memory_status=case.memory_status,
            reason=case.primary_reason,
            reason_category=case.reason_category,
            conditions=case.key_facts,
            evidence_element_ids=case.evidence_element_ids,
            version=case.version,
            source_id=str(case.source_document_id),
            created_at=case.created_at,
            updated_at=case.updated_at,
            open_flag_id=open_flag_id,
        )

    def _correction_view(
        self,
        memory: CorrectionMemoryModel,
        site: SiteModel,
        city_code: str,
        open_flag_id: str | None = None,
    ) -> MemoryView:
        """将人工纠错映射为统一记忆视图。"""
        return MemoryView(
            id=str(memory.id),
            memory_type="correction_memory",
            city_code=city_code,
            site_id=str(memory.site_id),
            site_name=site.site_name,
            source_label=f"任务纠错 · {str(memory.task_id)[:8]}",
            process_status=memory.status,
            memory_status=memory.memory_status,
            reason=memory.corrected_reason,
            reason_category=memory.reason_category,
            conditions=memory.applicability_conditions,
            evidence_element_ids=memory.supporting_element_ids,
            version=memory.version,
            source_id=str(memory.analysis_run_id),
            created_at=memory.created_at,
            updated_at=memory.updated_at,
            open_flag_id=open_flag_id,
        )

    @staticmethod
    def _process_allows_active(memory_type: MemoryType, target) -> bool:
        """只有业务流程已完成的记忆才允许手动恢复。"""
        return target.status == ("ready" if memory_type == "audit_case" else "confirmed")

    @staticmethod
    def _snapshot(memory_type: MemoryType, target) -> dict[str, object]:
        """保存足以还原旧业务语义的JSON快照，不包含内部对象。"""
        common = {
            "memory_type": memory_type,
            "memory_status": target.memory_status,
            "version": target.version,
        }
        if memory_type == "audit_case":
            return common | {
                "primary_reason": target.primary_reason,
                "reason_category": target.reason_category,
                "key_facts": target.key_facts,
                "evidence_element_ids": target.evidence_element_ids,
            }
        return common | {
            "corrected_reason": target.corrected_reason,
            "reason_category": target.reason_category,
            "applicability_conditions": target.applicability_conditions,
            "supporting_element_ids": target.supporting_element_ids,
        }

    @staticmethod
    def _flag_view(
        flag: MemoryFlagModel, memory_type: MemoryType, city_code: str
    ) -> MemoryFlagView:
        """把标错模型转换为安全REST响应。"""
        memory_id = flag.audit_case_id or flag.correction_memory_id
        return MemoryFlagView(
            id=str(flag.id),
            memory_type=memory_type,
            memory_id=str(memory_id),
            city_code=city_code,
            flag_type=flag.flag_type,
            description=flag.description,
            status=flag.status,
            previous_memory_status=flag.previous_memory_status,
            resolution_action=flag.resolution_action,
            resolution_note=flag.resolution_note,
            reported_at=flag.reported_at,
            resolved_at=flag.resolved_at,
        )

    def _audit(
        self,
        city_id: int,
        user: CurrentUser,
        action: str,
        memory_type: str,
        memory_id: UUID,
        before: dict,
        after: dict,
    ) -> None:
        """统一写入不可变审计日志。"""
        AuditLogRepository(self.session).append(
            city_id=city_id,
            user_id=UUID(user.id),
            action=action,
            entity_type=memory_type,
            entity_id=str(memory_id),
            before_data=before,
            after_data=after,
        )
