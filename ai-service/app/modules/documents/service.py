from __future__ import annotations

"""材料管理业务服务，协调数据库事务与文件存储。"""

from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.exceptions import AppError, ConflictError, ResourceNotFoundError
from app.core.identity import CurrentUser
from app.modules.audit_logs.repository import AuditLogRepository
from app.modules.audit_tasks.model import AuditTaskModel
from app.modules.cities.schemas import CityContext
from app.modules.documents.model import SourceDocumentModel
from app.modules.documents.repository import SourceDocumentRepository
from app.modules.documents.schemas import (
    DocumentStatus,
    DocumentType,
    IngestionMethod,
    SourceDocumentList,
    SourceDocumentUpdate,
    SourceDocumentView,
)
from app.modules.documents.storage import LocalDocumentStorage
from app.modules.sites.model import SiteModel


def _utc_now() -> datetime:
    """统一生成带时区的UTC时间。"""
    return datetime.now(timezone.utc)


def _parse_optional_uuid(value: str | None, field_name: str) -> UUID | None:
    """校验可选关联ID并返回数据库使用的UUID对象。"""
    if value is None:
        return None
    try:
        return UUID(value)
    except ValueError as exc:
        raise AppError(
            status=400,
            code="invalid_resource_id",
            title="资源标识无效",
            detail=f"{field_name}必须是有效的UUID",
        ) from exc


class SourceDocumentService:
    """提供材料上传、列表、下载和可恢复归档能力。"""

    def __init__(self, session: Session, storage: LocalDocumentStorage) -> None:
        self.session = session
        self.storage = storage
        self.repository = SourceDocumentRepository(session)

    def create(
        self,
        *,
        city: CityContext,
        user: CurrentUser,
        upload: UploadFile,
        document_type: DocumentType,
        title: str,
        site_id: str | None,
        task_id: str | None,
        ingestion_method: IngestionMethod = "manual_upload",
    ) -> SourceDocumentView:
        """保存原始文件并创建材料记录，任一步失败都会撤销本次落盘。"""
        normalized_site_id, normalized_task_id = self._resolve_relations(
            city=city,
            site_id=site_id,
            task_id=task_id,
        )
        if (
            document_type in {"current_report", "evidence_screenshot"}
            and normalized_task_id is None
        ):
            raise AppError(
                status=400,
                code="task_required_for_current_material",
                title="缺少稽核任务",
                detail="本次待分析报告或截图必须关联一个稽核任务",
            )
        if document_type == "current_report" and not (upload.filename or "").lower().endswith(
            ".docx"
        ):
            raise AppError(
                status=400,
                code="current_report_must_be_docx",
                title="本次报告格式不支持",
                detail="本次待分析报告必须使用DOCX格式，正文、表格和图片才能被可靠解析",
            )
        if document_type == "report_template" and normalized_task_id is not None:
            raise AppError(
                status=400,
                code="template_cannot_link_task",
                title="模板关联无效",
                detail="报告模板不能关联具体稽核任务",
            )

        document_id = uuid4()
        stored = self.storage.save(upload, city.code, document_id)
        try:
            # 本次报告属于具体任务，即使文件内容相同也可能是一次新的业务分析。
            duplicate = (
                None
                if document_type == "current_report"
                else self.repository.find_duplicate(city.id, document_type, stored.sha256)
            )
            if duplicate is not None:
                raise ConflictError(f"相同文件已上传，材料ID：{duplicate.id}")
            document = SourceDocumentModel(
                id=document_id,
                city_id=city.id,
                site_id=normalized_site_id,
                task_id=normalized_task_id,
                document_type=document_type,
                title=title.strip(),
                original_filename=stored.original_filename,
                media_type=stored.media_type,
                size_bytes=stored.size_bytes,
                sha256=stored.sha256,
                storage_key=stored.storage_key,
                ingestion_method=ingestion_method,
                status="uploaded",
                created_by=UUID(user.id),
            )
            self.repository.add(document)
            AuditLogRepository(self.session).append(
                city_id=city.id,
                user_id=UUID(user.id),
                action=(
                    "document.imported"
                    if ingestion_method == "batch_import"
                    else "document.uploaded"
                ),
                entity_type="source_document",
                entity_id=str(document.id),
                after_data={
                    "document_type": document.document_type,
                    "status": document.status,
                    "original_filename": document.original_filename,
                    "size_bytes": document.size_bytes,
                    "ingestion_method": document.ingestion_method,
                },
            )
            self.session.commit()
            self.session.refresh(document)
        except Exception:
            self.session.rollback()
            self.storage.discard(stored.storage_key)
            raise
        return self._to_view(document, city.code)

    def get(self, document_id: str, city: CityContext) -> SourceDocumentView:
        """获取当前城市的一份有效材料。"""
        document = self._get_model(document_id, city)
        return self._to_view(document, city.code)

    def list(
        self,
        *,
        city: CityContext,
        document_type: DocumentType | None,
        status: DocumentStatus | None,
        site_id: str | None,
        keyword: str | None,
        page: int,
        page_size: int,
    ) -> SourceDocumentList:
        """分页查询当前城市材料，默认隐藏已归档记录。"""
        normalized_site_id = _parse_optional_uuid(site_id, "site_id")
        rows, total = self.repository.list_page(
            city_id=city.id,
            document_type=document_type,
            status=status,
            site_id=normalized_site_id,
            keyword=keyword,
            page=page,
            page_size=page_size,
        )
        return SourceDocumentList(
            items=[self._to_view(row, city.code) for row in rows],
            total=total,
            page=page,
            page_size=page_size,
        )

    def resolve_content(self, document_id: str, city: CityContext) -> tuple[str, str, str]:
        """返回下载所需的物理路径、原始文件名和可信媒体类型。"""
        document = self._get_model(document_id, city)
        path = self.storage.resolve(document.storage_key)
        return str(path), document.original_filename, document.media_type

    def update(
        self,
        document_id: str,
        city: CityContext,
        user: CurrentUser,
        payload: SourceDocumentUpdate,
    ) -> SourceDocumentView:
        """关联或解除历史报告的报账点，并记录完整审计轨迹。"""
        document = self._get_model(document_id, city)
        if document.document_type != "historical_report":
            raise AppError(
                status=400,
                code="site_link_not_supported",
                title="材料不支持该操作",
                detail="当前只有历史稽核报告可以单独关联报账点",
            )
        normalized_site_id, _ = self._resolve_relations(
            city=city,
            site_id=payload.site_id,
            task_id=None,
        )
        previous_site_id = document.site_id
        if previous_site_id == normalized_site_id:
            return self._to_view(document, city.code)

        document.site_id = normalized_site_id
        document.updated_at = _utc_now()
        document.version += 1
        AuditLogRepository(self.session).append(
            city_id=city.id,
            user_id=UUID(user.id),
            action="document.site_updated",
            entity_type="source_document",
            entity_id=str(document.id),
            before_data={"site_id": str(previous_site_id) if previous_site_id else None},
            after_data={"site_id": str(normalized_site_id) if normalized_site_id else None},
        )
        self.session.commit()
        self.session.refresh(document)
        return self._to_view(document, city.code)

    def archive(self, document_id: str, city: CityContext, user: CurrentUser) -> None:
        """软归档材料并保留原文件，便于审计和后续恢复。"""
        document = self._get_model(document_id, city)
        previous_status = document.status
        now = _utc_now()
        document.status = "archived"
        document.archived_at = now
        document.updated_at = now
        document.version += 1
        AuditLogRepository(self.session).append(
            city_id=city.id,
            user_id=UUID(user.id),
            action="document.archived",
            entity_type="source_document",
            entity_id=str(document.id),
            before_data={"status": previous_status},
            after_data={"status": "archived"},
        )
        self.session.commit()

    def _get_model(self, document_id: str, city: CityContext) -> SourceDocumentModel:
        """复用城市隔离查询并统一404响应。"""
        document = self.repository.get(document_id, city.id)
        if document is None:
            raise ResourceNotFoundError("材料不存在")
        return document

    def _resolve_relations(
        self, *, city: CityContext, site_id: str | None, task_id: str | None
    ) -> tuple[UUID | None, UUID | None]:
        """校验报账点和任务均属于当前城市，并从任务推导报账点。"""
        normalized_site_id = _parse_optional_uuid(site_id, "site_id")
        normalized_task_id = _parse_optional_uuid(task_id, "task_id")
        if normalized_site_id is not None:
            site_exists = self.session.scalar(
                select(SiteModel.id).where(
                    SiteModel.id == normalized_site_id,
                    SiteModel.city_id == city.id,
                    SiteModel.status == "active",
                )
            )
            if site_exists is None:
                raise ResourceNotFoundError("报账点不存在")
        if normalized_task_id is not None:
            task = self.session.scalar(
                select(AuditTaskModel).where(
                    AuditTaskModel.id == normalized_task_id,
                    AuditTaskModel.city_id == city.id,
                )
            )
            if task is None:
                raise ResourceNotFoundError("稽核任务不存在")
            if normalized_site_id is not None and normalized_site_id != task.site_id:
                raise AppError(
                    status=400,
                    code="site_task_mismatch",
                    title="关联关系无效",
                    detail="报账点与稽核任务不匹配",
                )
            normalized_site_id = task.site_id
        return normalized_site_id, normalized_task_id

    def _to_view(self, document: SourceDocumentModel, city_code: str) -> SourceDocumentView:
        """隐藏内部存储键并生成稳定的下载资源地址。"""
        return SourceDocumentView(
            id=str(document.id),
            city_code=city_code,
            site_id=str(document.site_id) if document.site_id else None,
            task_id=str(document.task_id) if document.task_id else None,
            document_type=document.document_type,
            title=document.title,
            original_filename=document.original_filename,
            media_type=document.media_type,
            size_bytes=document.size_bytes,
            sha256=document.sha256,
            ingestion_method=document.ingestion_method,
            status=document.status,
            download_url=f"/api/v1/documents/{document.id}/content",
            created_by=str(document.created_by),
            created_at=document.created_at,
            updated_at=document.updated_at,
            archived_at=document.archived_at,
        )
