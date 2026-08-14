from __future__ import annotations

"""报告草稿落库、版本编辑、审核流转和正式Word生成服务。"""

from datetime import datetime, timezone
from uuid import UUID, uuid4

from sqlalchemy.orm import Session

from app.core.exceptions import ConflictError, ResourceNotFoundError
from app.core.identity import CurrentUser
from app.modules.analysis_runs.repository import AnalysisRunRepository
from app.modules.audit_logs.repository import AuditLogRepository
from app.modules.audit_tasks.repository import AuditTaskRepository
from app.modules.cities.schemas import CityContext
from app.modules.documents.repository import SourceDocumentRepository
from app.modules.documents.storage import LocalDocumentStorage
from app.modules.reports.docx_renderer import render_report_docx
from app.modules.reports.model import ReportModel, ReportVersionModel
from app.modules.reports.repository import ReportRepository
from app.modules.reports.schemas import (
    ReportReview,
    ReportUpdate,
    ReportVersionSummary,
    ReportVersionView,
    ReportView,
)


def _utc_now() -> datetime:
    """统一生成带时区UTC时间。"""
    return datetime.now(timezone.utc)


class ReportService:
    """维护报告完整生命周期，同时保证城市隔离和版本留痕。"""

    def __init__(self, session: Session, storage: LocalDocumentStorage) -> None:
        self.session = session
        self.storage = storage
        self.repository = ReportRepository(session)
        self.audit_logs = AuditLogRepository(session)

    def _version_view(self, version: ReportVersionModel) -> ReportVersionView:
        """把数据库版本转换为稳定的接口结构。"""
        return ReportVersionView(
            version_no=version.version_no,
            title=version.title,
            sections=version.sections,
            uncertain_items=version.uncertain_items,
            review_reasons=version.review_reasons,
            change_summary=version.change_summary,
            has_docx=bool(version.docx_storage_key),
            docx_sha256=version.docx_sha256,
            docx_size_bytes=version.docx_size_bytes,
            created_by=str(version.created_by),
            created_at=version.created_at,
            generated_at=version.generated_at,
        )

    def _to_view(self, report: ReportModel, city_code: str) -> ReportView:
        """组装当前正文和精简版本历史。"""
        versions = self.repository.list_versions(report.id, report.city_id)
        by_number = {item.version_no: item for item in versions}
        current = by_number.get(report.current_version)
        if current is None:
            raise ResourceNotFoundError("报告当前版本不存在")
        return ReportView(
            id=str(report.id),
            city_code=city_code,
            site_id=str(report.site_id),
            task_id=str(report.task_id),
            analysis_run_id=str(report.analysis_run_id),
            status=report.status,
            current_version=report.current_version,
            approved_version=report.approved_version,
            review_note=report.review_note,
            current=self._version_view(current),
            versions=[
                ReportVersionSummary(
                    version_no=item.version_no,
                    change_summary=item.change_summary,
                    created_by=str(item.created_by),
                    created_at=item.created_at,
                    has_docx=bool(item.docx_storage_key),
                )
                for item in versions
            ],
            content_url=(
                f"/api/v1/reports/{report.id}/content" if report.status == "approved" else None
            ),
            created_by=str(report.created_by),
            reviewed_by=str(report.reviewed_by) if report.reviewed_by else None,
            created_at=report.created_at,
            updated_at=report.updated_at,
            reviewed_at=report.reviewed_at,
        )

    def create_from_run(self, run_id: str, city: CityContext, user: CurrentUser) -> ReportView:
        """把一次完成的AI报告草稿幂等保存为版本1。"""
        existing = self.repository.get_by_run(run_id, city.id)
        if existing is not None:
            return self._to_view(existing, city.code)
        run = AnalysisRunRepository(self.session).get(run_id, city.id)
        if run is None:
            raise ResourceNotFoundError("分析运行不存在")
        if run.status != "completed" or not isinstance(run.result, dict):
            raise ConflictError("只有已完成的分析运行才能创建报告")
        draft = run.result.get("report_draft")
        if not isinstance(draft, dict) or not isinstance(draft.get("sections"), list):
            raise ConflictError("该分析运行还没有可编辑的报告草稿")
        task_row = AuditTaskRepository(self.session).get(run.task_id, city.id)
        if task_row is None:
            raise ResourceNotFoundError("报告所属任务不存在")
        task, _site = task_row
        document_ids: list[UUID] = []
        try:
            document_ids = [UUID(item) for item in run.material_refs]
        except ValueError as exc:
            raise ConflictError("分析运行引用的原始材料无效") from exc
        documents = SourceDocumentRepository(self.session).list_by_ids(document_ids, city.id)
        current_documents = [item for item in documents if item.document_type == "current_report"]
        if len(current_documents) != 1:
            raise ConflictError("正式Word只能从一份本次DOCX报告生成")

        now = _utc_now()
        report = ReportModel(
            id=uuid4(),
            city_id=city.id,
            site_id=task.site_id,
            task_id=task.id,
            analysis_run_id=run.id,
            status="draft",
            current_version=1,
            created_by=UUID(user.id),
            created_at=now,
            updated_at=now,
        )
        version = ReportVersionModel(
            id=uuid4(),
            city_id=city.id,
            report_id=report.id,
            version_no=1,
            title=str(draft.get("title", "电费超标稽核报告")),
            sections=draft["sections"],
            uncertain_items=list(draft.get("uncertain_items", [])),
            review_reasons=list(draft.get("review_reasons", [])),
            change_summary="由AI分析结果创建初始草稿",
            source_document_id=current_documents[0].id,
            created_by=UUID(user.id),
            created_at=now,
        )
        self.repository.add(report, version)
        self.audit_logs.append(
            city_id=city.id,
            user_id=UUID(user.id),
            action="report.created",
            entity_type="report",
            entity_id=str(report.id),
            after_data={"status": "draft", "version": 1, "analysis_run_id": str(run.id)},
        )
        self.session.commit()
        return self._to_view(report, city.code)

    def get(self, report_id: str, city: CityContext) -> ReportView:
        """读取当前城市报告。"""
        report = self.repository.get(report_id, city.id)
        if report is None:
            raise ResourceNotFoundError("报告不存在")
        return self._to_view(report, city.code)

    def update(
        self,
        report_id: str,
        city: CityContext,
        user: CurrentUser,
        payload: ReportUpdate,
    ) -> ReportView:
        """保存完整新版本；已提交或已通过的版本不能直接修改。"""
        report = self.repository.get(report_id, city.id)
        if report is None:
            raise ResourceNotFoundError("报告不存在")
        if report.status not in {"draft", "returned"}:
            raise ConflictError("当前报告状态不允许修改，请先退回后再编辑")
        previous = self.repository.get_version(report.id, report.current_version, city.id)
        if previous is None:
            raise ResourceNotFoundError("报告当前版本不存在")
        previous_by_code = {item["section_code"]: item for item in previous.sections}
        sections: list[dict[str, object]] = []
        for edited in payload.sections:
            old = previous_by_code[edited.section_code]
            # 人工只能改标题和文字，AI生成时已经校验过的证据引用由后端原样继承。
            sections.append(
                {
                    "section_code": edited.section_code,
                    "title": edited.title.strip(),
                    "content": edited.content.strip(),
                    "supporting_current_element_ids": old.get("supporting_current_element_ids", []),
                    "supporting_history_element_ids": old.get("supporting_history_element_ids", []),
                    "calculation_references": old.get("calculation_references", []),
                }
            )
        new_number = report.current_version + 1
        now = _utc_now()
        version = ReportVersionModel(
            id=uuid4(),
            city_id=city.id,
            report_id=report.id,
            version_no=new_number,
            title=payload.title.strip(),
            sections=sections,
            uncertain_items=previous.uncertain_items,
            review_reasons=previous.review_reasons,
            change_summary=payload.change_summary.strip(),
            source_document_id=previous.source_document_id,
            created_by=UUID(user.id),
            created_at=now,
        )
        self.repository.add_version(version)
        before = {"status": report.status, "version": report.current_version}
        report.current_version = new_number
        report.status = "draft"
        report.review_note = None
        report.updated_at = now
        self.audit_logs.append(
            city_id=city.id,
            user_id=UUID(user.id),
            action="report.version_created",
            entity_type="report",
            entity_id=str(report.id),
            before_data=before,
            after_data={"status": "draft", "version": new_number},
        )
        self.session.commit()
        return self._to_view(report, city.code)

    def review(
        self,
        report_id: str,
        city: CityContext,
        user: CurrentUser,
        payload: ReportReview,
    ) -> ReportView:
        """执行提交、退回或通过动作；通过时同步冻结并生成正式Word。"""
        report = self.repository.get(report_id, city.id)
        if report is None:
            raise ResourceNotFoundError("报告不存在")
        allowed = {
            "submit": ({"draft", "returned"}, "in_review"),
            "return": ({"in_review"}, "returned"),
            "approve": ({"in_review"}, "approved"),
        }
        source_statuses, target_status = allowed[payload.action]
        if report.status not in source_statuses:
            raise ConflictError(f"报告当前为{report.status}，不能执行{payload.action}操作")

        before_status = report.status
        now = _utc_now()
        generated_key: str | None = None
        if payload.action == "approve":
            version = self.repository.get_version(report.id, report.current_version, city.id)
            if version is None:
                raise ResourceNotFoundError("报告当前版本不存在")
            source_document = SourceDocumentRepository(self.session).get(
                version.source_document_id, city.id
            )
            if source_document is None or source_document.document_type != "current_report":
                raise ConflictError("报告原始DOCX不存在，无法生成正式Word")
            source_path = self.storage.resolve(source_document.storage_key)
            content = render_report_docx(source_path.read_bytes(), version.title, version.sections)
            stored = self.storage.save_report(
                city_code=city.code,
                report_id=report.id,
                version_no=version.version_no,
                content=content,
            )
            generated_key = stored.storage_key
            version.docx_storage_key = stored.storage_key
            version.docx_sha256 = stored.sha256
            version.docx_size_bytes = stored.size_bytes
            version.generated_at = now
            report.approved_version = version.version_no
            report.reviewed_by = UUID(user.id)
            report.reviewed_at = now
        elif payload.action == "return":
            report.reviewed_by = UUID(user.id)
            report.reviewed_at = now

        report.status = target_status
        report.review_note = (payload.note or "").strip() or None
        report.updated_at = now
        self.audit_logs.append(
            city_id=city.id,
            user_id=UUID(user.id),
            action=f"report.{payload.action}",
            entity_type="report",
            entity_id=str(report.id),
            before_data={"status": before_status, "version": report.current_version},
            after_data={
                "status": target_status,
                "version": report.current_version,
                "note": report.review_note,
            },
        )
        try:
            self.session.commit()
        except Exception:
            self.session.rollback()
            if generated_key:
                self.storage.discard(generated_key)
            raise
        return self._to_view(report, city.code)

    def approved_file(self, report_id: str, city: CityContext) -> tuple[str, str]:
        """返回已通过版本的存储键和安全下载文件名。"""
        report = self.repository.get(report_id, city.id)
        if report is None:
            raise ResourceNotFoundError("报告不存在")
        if report.status != "approved" or report.approved_version is None:
            raise ConflictError("报告尚未审核通过，不能下载正式Word")
        version = self.repository.get_version(report.id, report.approved_version, city.id)
        if version is None or not version.docx_storage_key:
            raise ResourceNotFoundError("正式Word文件不存在")
        return version.docx_storage_key, f"{version.title}.docx"
