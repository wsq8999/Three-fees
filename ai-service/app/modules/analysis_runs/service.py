from __future__ import annotations

"""分析运行服务，连接REST任务资源与LangGraph工作流。"""

from datetime import datetime, timezone
from typing import Any
from uuid import UUID, uuid4

from langgraph.types import Command
from sqlalchemy.orm import Session

from app.agents.audit.context import AuditAgentContext
from app.agents.audit.persistence import open_persistent_audit_graph
from app.agents.audit.state import AuditAgentState
from app.core.config import get_settings
from app.core.exceptions import AppError, ConflictError, ResourceNotFoundError
from app.core.identity import CurrentUser
from app.db.session import SessionLocal
from app.integrations.ai.base import AIProviderError
from app.integrations.ai.factory import get_ai_provider
from app.modules.analysis_runs.context_builder import AnalysisContextBuilder
from app.modules.analysis_runs.model import AnalysisRunModel
from app.modules.analysis_runs.repository import AnalysisRunRepository
from app.modules.analysis_runs.schemas import (
    AnalysisEvent,
    AnalysisRunCreate,
    AnalysisRunResume,
    AnalysisRunView,
)
from app.modules.audit_tasks.service import AuditTaskService
from app.modules.cities.schemas import CityContext
from app.modules.documents.parse_repository import DocumentParseRepository
from app.modules.documents.repository import SourceDocumentRepository
from app.modules.documents.runtime_reader import LocalRuntimeMaterialReader
from app.modules.documents.storage import LocalDocumentStorage

NODE_PROGRESS = {
    "validate_input": 10,
    "parse_documents": 25,
    "extract_facts": 50,
    "validate_metrics": 60,
    "decide_over_limit": 65,
    "confirm_over_limit": 65,
    "finish_without_audit": 100,
    "retrieve_evidence": 70,
    "judge_reason": 85,
    "draft_report": 95,
}


def _utc_now() -> datetime:
    """统一生成带时区的UTC时间。"""
    return datetime.now(timezone.utc)


def _safe_snapshot(state: dict[str, Any]) -> dict[str, Any]:
    """只持久化可恢复和可展示字段，排除事件累加器与运行时依赖。"""
    keys = (
        "task_context",
        "current_materials",
        "history_candidates",
        "correction_memories",
        "retrieval_summary",
        "facts",
        "calculations",
        "screening",
        "evidence",
        "judgment",
        "report_draft",
    )
    return {
        key: state.get(key, {} if key not in {"evidence", "history_candidates"} else [])
        for key in keys
    }


def _build_runtime_context(
    *,
    session: Session,
    run: AnalysisRunModel,
    city_id: int,
    storage: LocalDocumentStorage,
    state: dict[str, Any],
) -> AuditAgentContext:
    """根据检查点冻结的材料范围重建非持久化依赖。

    恢复时绝不重新检索最新历史材料，否则暂停前后的RAG上下文会发生漂移。这里只根据
    状态中的当前材料和历史候选文档ID重建读取白名单；城市ID仍参与每一次数据库查询。
    """
    current_ids = [UUID(str(item)) for item in run.material_refs]
    history_ids = [
        UUID(str(item["document_id"]))
        for item in state.get("history_candidates", [])
        if isinstance(item, dict) and item.get("document_id")
    ]
    document_repository = SourceDocumentRepository(session)
    current_documents = document_repository.list_by_ids(current_ids, city_id)
    history_documents = document_repository.list_by_ids(history_ids, city_id)
    parse_repository = DocumentParseRepository(session)
    all_elements = []
    for document in [*current_documents, *history_documents]:
        parse_run = parse_repository.latest_completed_run(document.id, city_id)
        if parse_run is not None:
            all_elements.extend(parse_repository.list_elements(parse_run.id, city_id))

    settings = get_settings()
    return AuditAgentContext(
        run_id=str(run.id),
        user_id=str(run.created_by),
        ai_provider=get_ai_provider(),
        material_reader=LocalRuntimeMaterialReader(
            storage,
            [*current_documents, *history_documents],
            all_elements,
        ),
        extract_reasoning_effort=settings.kimi_extract_reasoning_effort,
        judge_reasoning_effort=settings.kimi_judge_reasoning_effort,
    )


class AnalysisRunService:
    """管理一次分析的创建、执行、进度和安全结果。"""

    def __init__(self, session: Session) -> None:
        self.session = session
        self.repository = AnalysisRunRepository(session)

    def _to_view(self, run: AnalysisRunModel, city_code: str) -> AnalysisRunView:
        """组合运行和事件，形成稳定的REST响应。"""
        events = self.repository.list_events(str(run.id), run.city_id)
        error = None
        if run.error_code:
            error = {"code": run.error_code, "message": run.error_message or ""}
        return AnalysisRunView(
            id=str(run.id),
            task_id=str(run.task_id),
            city_code=city_code,
            status=run.status,
            progress=run.progress,
            current_node=run.current_node,
            material_refs=run.material_refs,
            events=[
                AnalysisEvent(
                    sequence=item.sequence_no,
                    node=item.node_name,
                    message=item.message,
                    created_at=item.created_at,
                )
                for item in events
            ],
            pending_interrupt=run.pending_interrupt,
            result=run.result,
            error=error,
            created_by=str(run.created_by),
            created_at=run.created_at,
            started_at=run.started_at,
            finished_at=run.finished_at,
        )

    def create(
        self,
        *,
        task_id: str,
        city: CityContext,
        user: CurrentUser,
        payload: AnalysisRunCreate,
    ) -> AnalysisRunView:
        """创建排队中的运行，并阻止同任务重复执行。"""
        task = AuditTaskService(self.session).get_model(task_id, city)
        material_refs = self._validate_material_refs(payload.material_refs, task, city)
        if self.repository.has_active_run(task_id, city.id):
            raise ConflictError("该任务已有正在执行的分析运行")
        run = AnalysisRunModel(
            id=uuid4(),
            city_id=city.id,
            task_id=task.id,
            run_no=self.repository.next_run_no(task_id),
            status="queued",
            progress=0,
            current_node="queued",
            workflow_version="screening-interrupt-v2",
            material_refs=material_refs,
            state_snapshot={},
            created_by=UUID(user.id),
        )
        self.repository.add(run)
        self.session.commit()
        self.session.refresh(run)
        return self._to_view(run, city.code)

    def _validate_material_refs(
        self,
        material_refs: list[str],
        task,
        city: CityContext,
    ) -> list[str]:
        """确保输入是当前任务的整份DOCX，或辅助调试使用的独立截图。"""
        unique_refs = list(dict.fromkeys(material_refs))
        if not unique_refs:
            raise AppError(
                status=400,
                code="current_material_required",
                title="缺少本次稽核材料",
                detail="启动分析前必须上传一份本次DOCX报告或至少一张辅助截图",
            )
        try:
            document_ids = [UUID(item) for item in unique_refs]
        except ValueError as exc:
            raise AppError(
                status=400,
                code="invalid_material_ref",
                title="材料标识无效",
                detail="material_refs必须全部是有效的材料UUID",
            ) from exc
        documents = SourceDocumentRepository(self.session).list_by_ids(document_ids, city.id)
        by_id = {document.id: document for document in documents}
        if any(document_id not in by_id for document_id in document_ids):
            raise ResourceNotFoundError("本次稽核材料不存在")
        document_types = {by_id[item].document_type for item in document_ids}
        if "current_report" in document_types and len(document_ids) != 1:
            raise AppError(
                status=400,
                code="single_current_report_required",
                title="本次报告数量不正确",
                detail="一次分析只能选择一份本次DOCX报告，不能与独立截图混用",
            )
        if document_types not in ({"current_report"}, {"evidence_screenshot"}):
            raise AppError(
                status=400,
                code="invalid_analysis_material_type",
                title="材料类型不正确",
                detail="分析运行只能使用一份本次DOCX报告，或使用辅助截图模式",
            )
        for document_id in document_ids:
            document = by_id[document_id]
            if document.document_type == "current_report" and document.status != "parsed":
                raise AppError(
                    status=400,
                    code="current_report_not_parsed",
                    title="本次报告尚未解析完成",
                    detail="请先成功解析DOCX中的正文、表格和图片，再启动Agent分析",
                )
            if document.task_id != task.id or document.site_id != task.site_id:
                raise AppError(
                    status=400,
                    code="analysis_material_task_mismatch",
                    title="材料与任务不匹配",
                    detail="本次报告或截图必须属于当前任务和当前报账点",
                )
        return [str(item) for item in document_ids]

    def get(self, run_id: str, city: CityContext) -> AnalysisRunView:
        """获取当前城市的一次分析运行。"""
        run = self.repository.get(run_id, city.id)
        if run is None:
            raise ResourceNotFoundError("分析运行不存在")
        return self._to_view(run, city.code)

    def list(self, city: CityContext) -> list[AnalysisRunView]:
        """列出当前城市全部分析运行。"""
        return [self._to_view(run, city.code) for run in self.repository.list_by_city(city.id)]

    def queue_resume(
        self,
        *,
        run_id: str,
        city: CityContext,
        user: CurrentUser,
        payload: AnalysisRunResume,
    ) -> AnalysisRunView:
        """把一次人工确认原子地转换为待恢复状态，重复或跨城市请求都会被拒绝。"""
        run = self.repository.get(run_id, city.id)
        if run is None:
            # 城市条件在查询内生效，因此不会向调用者泄露其他城市是否存在该运行。
            raise ResourceNotFoundError("分析运行不存在")
        pending = run.pending_interrupt or {}
        if run.status != "waiting_input" or pending.get("type") != "confirm_system_over_limit":
            raise ConflictError("该分析当前没有可恢复的系统超标确认")

        run.status = "queued"
        run.current_node = "resume_queued"
        run.error_code = None
        run.error_message = None
        # 记录谁触发了恢复用于审计；业务选择仍通过后台参数传给LangGraph，不能由前端
        # 直接改写checkpoint或screening状态。
        run.pending_interrupt = {**pending, "resume_requested_by": user.id}
        self.repository.append_event(
            run=run,
            node_name="confirm_over_limit",
            event_type="resume_queued",
            message=(
                "人工已确认系统超标，等待继续稽核"
                if payload.decision == "confirm_over_limit"
                else "人工已确认系统未超标，等待结束流程"
            ),
        )
        self.session.commit()
        self.session.refresh(run)
        return self._to_view(run, city.code)

    @staticmethod
    def execute(
        run_id: str,
        city_id: int,
        city_code: str,
        storage: LocalDocumentStorage,
    ) -> None:
        """从头执行工作流；遇到LangGraph interrupt时保存waiting_input并正常返回。"""
        with SessionLocal() as session:
            repository = AnalysisRunRepository(session)
            run = repository.get(run_id, city_id)
            if run is None:
                return
            run.status = "running"
            run.started_at = _utc_now()
            run.current_node = "validate_input"
            session.commit()

            try:
                context = AnalysisContextBuilder(session).build(
                    task_id=str(run.task_id),
                    city_id=city_id,
                    city_code=city_code,
                    material_refs=run.material_refs,
                )
                state: AuditAgentState = {
                    "task_id": str(run.task_id),
                    "city_code": city_code,
                    "material_refs": run.material_refs,
                    "task_context": context["task_context"],
                    "current_materials": context["current_materials"],
                    "history_candidates": context["history_candidates"],
                    "correction_memories": context["correction_memories"],
                    "retrieval_summary": context["retrieval_summary"],
                    "facts": {},
                    "calculations": {},
                    "screening": {},
                    "evidence": [],
                    "judgment": {},
                    "report_draft": {},
                    "events": [],
                }
                runtime_context = _build_runtime_context(
                    session=session,
                    run=run,
                    city_id=city_id,
                    storage=storage,
                    state=state,
                )
                config = {"configurable": {"thread_id": run_id}}
                with open_persistent_audit_graph() as graph:
                    AnalysisRunService._drive_graph(
                        graph=graph,
                        graph_input=state,
                        config=config,
                        runtime_context=runtime_context,
                        session=session,
                        repository=repository,
                        run=run,
                    )
            except Exception as exc:
                AnalysisRunService._mark_failed(
                    session=session,
                    repository=repository,
                    run_id=run_id,
                    city_id=city_id,
                    error=exc,
                )

    @staticmethod
    def resume_execute(
        run_id: str,
        city_id: int,
        city_code: str,
        storage: LocalDocumentStorage,
        resume_payload: dict[str, Any],
    ) -> None:
        """使用同一thread_id和PostgreSQL检查点恢复原运行，而不是创建一条新运行。"""
        del city_code  # 城市隔离使用数据库city_id；city_code已经冻结在checkpoint状态中。
        with SessionLocal() as session:
            repository = AnalysisRunRepository(session)
            run = repository.get(run_id, city_id)
            if run is None or run.status != "queued":
                return
            run.status = "running"
            run.current_node = "confirm_over_limit"
            session.commit()
            config = {"configurable": {"thread_id": run_id}}
            try:
                with open_persistent_audit_graph() as graph:
                    checkpoint_state = dict(graph.get_state(config).values)
                    if not checkpoint_state:
                        raise RuntimeError("未找到可恢复的LangGraph检查点")
                    runtime_context = _build_runtime_context(
                        session=session,
                        run=run,
                        city_id=city_id,
                        storage=storage,
                        state=checkpoint_state,
                    )
                    AnalysisRunService._drive_graph(
                        graph=graph,
                        graph_input=Command(resume=resume_payload),
                        config=config,
                        runtime_context=runtime_context,
                        session=session,
                        repository=repository,
                        run=run,
                    )
            except Exception as exc:
                AnalysisRunService._mark_failed(
                    session=session,
                    repository=repository,
                    run_id=run_id,
                    city_id=city_id,
                    error=exc,
                )

    @staticmethod
    def _drive_graph(
        *,
        graph: Any,
        graph_input: AuditAgentState | Command,
        config: dict[str, Any],
        runtime_context: AuditAgentContext,
        session: Session,
        repository: AnalysisRunRepository,
        run: AnalysisRunModel,
    ) -> None:
        """消费节点更新，在中断或完成这两个事务边界上持久化业务运行状态。"""
        # 恢复时以checkpoint为准；首次运行则使用传入初始状态。每个节点单独提交，页面
        # 轮询看到的是已持久化进度，而不是前端猜测的动画百分比。
        checkpoint_values = dict(graph.get_state(config).values)
        merged: dict[str, Any] = checkpoint_values or (
            dict(graph_input) if isinstance(graph_input, dict) else {}
        )
        for update in graph.stream(
            graph_input,
            config=config,
            context=runtime_context,
            stream_mode="updates",
        ):
            node_name, node_output = next(iter(update.items()))
            if node_name == "__interrupt__":
                interrupts = tuple(node_output)
                if len(interrupts) != 1:
                    raise RuntimeError("当前流程只允许存在一个待处理人工中断")
                interrupt_item = interrupts[0]
                payload = interrupt_item.value
                if not isinstance(payload, dict) or payload.get("type") != (
                    "confirm_system_over_limit"
                ):
                    raise RuntimeError("收到未知类型的LangGraph中断")
                merged = dict(graph.get_state(config).values)
                run.status = "waiting_input"
                run.current_node = "confirm_over_limit"
                run.progress = NODE_PROGRESS["confirm_over_limit"]
                run.state_snapshot = _safe_snapshot(merged)
                run.pending_interrupt = {"id": interrupt_item.id, **payload}
                run.finished_at = None
                repository.append_event(
                    run=run,
                    node_name="confirm_over_limit",
                    event_type="interrupted",
                    message="系统是否超标无法可靠确认，等待人工选择后继续",
                )
                session.commit()
                return

            if not isinstance(node_output, dict):
                raise RuntimeError(f"节点{node_name}返回了无效状态")
            for key, value in node_output.items():
                if key == "events":
                    merged["events"] = [*merged.get("events", []), *value]
                else:
                    merged[key] = value
            run.current_node = node_name
            run.progress = NODE_PROGRESS[node_name]
            run.state_snapshot = _safe_snapshot(merged)
            for event in node_output.get("events", []):
                repository.append_event(
                    run=run,
                    node_name=event["node"],
                    event_type="completed",
                    message=event["message"],
                )
            session.commit()

        final_state = dict(graph.get_state(config).values) or merged
        run.state_snapshot = _safe_snapshot(final_state)
        screening_status = run.state_snapshot.get("screening", {}).get("status")
        mode = "screening_not_over_limit" if screening_status == "no" else "report_draft_ready"
        run.status = "completed"
        run.progress = 100
        run.current_node = "completed"
        run.pending_interrupt = None
        run.result = {"mode": mode, **run.state_snapshot}
        run.finished_at = _utc_now()
        session.commit()

    @staticmethod
    def _mark_failed(
        *,
        session: Session,
        repository: AnalysisRunRepository,
        run_id: str,
        city_id: int,
        error: Exception,
    ) -> None:
        """统一记录失败结果，不把供应商异常或内部堆栈直接暴露给前端。"""
        session.rollback()
        run = repository.get(run_id, city_id)
        if run is None:
            return
        run.status = "failed"
        run.current_node = "failed"
        if isinstance(error, AIProviderError):
            run.error_code = error.code
            run.error_message = str(error)
        else:
            run.error_code = "agent_execution_failed"
            run.error_message = "Agent执行失败，请根据运行ID查看服务日志"
        run.finished_at = _utc_now()
        session.commit()
