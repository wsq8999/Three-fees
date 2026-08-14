from __future__ import annotations

"""确定性文档解析服务，负责运行状态、元素入库和图片落盘。"""

from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID, uuid4

from sqlalchemy.orm import Session

from app.core.exceptions import ConflictError, ResourceNotFoundError
from app.core.identity import CurrentUser
from app.modules.audit_logs.repository import AuditLogRepository
from app.modules.cities.schemas import CityContext
from app.modules.documents.model import (
    DocumentElementModel,
    DocumentParseRunModel,
    SourceDocumentModel,
)
from app.modules.documents.parse_repository import DocumentParseRepository
from app.modules.documents.parser import DocumentParseError, DocxParser
from app.modules.documents.repository import SourceDocumentRepository
from app.modules.documents.schemas import (
    DocumentElementList,
    DocumentElementView,
    DocumentParseRunView,
)
from app.modules.documents.storage import LocalDocumentStorage


def _utc_now() -> datetime:
    """统一生成带时区的UTC时间。"""
    return datetime.now(timezone.utc)


class DocumentParsingService:
    """把可重复执行的解析过程保存成独立运行资源。"""

    def __init__(self, session: Session, storage: LocalDocumentStorage) -> None:
        self.session = session
        self.storage = storage
        self.document_repository = SourceDocumentRepository(session)
        self.parse_repository = DocumentParseRepository(session)

    def create_and_execute(
        self, document_id: str, city: CityContext, user: CurrentUser
    ) -> DocumentParseRunView:
        """创建并同步执行一次解析；失败也保留可查询的运行记录。"""
        document = self._get_document(document_id, city)
        if self.parse_repository.has_active_run(document.id, city.id):
            raise ConflictError("该材料已有正在执行的解析")

        parser_name, parser_version = self._parser_identity(document.original_filename)
        run = DocumentParseRunModel(
            id=uuid4(),
            city_id=city.id,
            document_id=document.id,
            run_no=self.parse_repository.next_run_no(document.id),
            status="queued",
            parser_name=parser_name,
            parser_version=parser_version,
            element_count=0,
            created_by=UUID(user.id),
        )
        self.parse_repository.add_run(run)
        document.status = "parsing"
        document.updated_at = _utc_now()
        document.version += 1
        self.session.commit()

        run.status = "running"
        run.started_at = _utc_now()
        self.session.commit()
        try:
            self._execute_docx(document, run, city)
            run.status = "completed"
            run.finished_at = _utc_now()
            document.status = "parsed"
            document.updated_at = run.finished_at
            document.version += 1
            AuditLogRepository(self.session).append(
                city_id=city.id,
                user_id=UUID(user.id),
                action="document.parsed",
                entity_type="source_document",
                entity_id=str(document.id),
                after_data={
                    "parse_run_id": str(run.id),
                    "parser_version": run.parser_version,
                    "element_count": run.element_count,
                },
            )
            self.session.commit()
        except Exception as exc:
            self.session.rollback()
            self.storage.discard_asset_group(city.code, document.id, run.id)
            run = self.parse_repository.get_run(run.id, city.id)
            document = self.document_repository.get(document.id, city.id)
            if run is None or document is None:
                raise
            error_code = (
                exc.code if isinstance(exc, DocumentParseError) else "document_parse_failed"
            )
            error_message = str(exc)[:500] or "材料解析失败"
            run.status = "failed"
            run.error_code = error_code
            run.error_message = error_message
            run.finished_at = _utc_now()
            document.status = "failed"
            document.updated_at = run.finished_at
            document.version += 1
            AuditLogRepository(self.session).append(
                city_id=city.id,
                user_id=UUID(user.id),
                action="document.parse_failed",
                entity_type="source_document",
                entity_id=str(document.id),
                after_data={"parse_run_id": str(run.id), "error_code": error_code},
            )
            self.session.commit()
        self.session.refresh(run)
        return self._run_to_view(run, city.code)

    def get_run(self, run_id: str, city: CityContext) -> DocumentParseRunView:
        """获取当前城市的解析运行状态。"""
        run = self.parse_repository.get_run(run_id, city.id)
        if run is None:
            raise ResourceNotFoundError("文档解析运行不存在")
        return self._run_to_view(run, city.code)

    def list_latest_elements(self, document_id: str, city: CityContext) -> DocumentElementList:
        """获取材料最近一次成功解析的顺序元素。"""
        document = self._get_document(document_id, city)
        run = self.parse_repository.latest_completed_run(document.id, city.id)
        if run is None:
            raise ResourceNotFoundError("材料尚无成功解析结果")
        rows = self.parse_repository.list_elements(run.id, city.id)
        return DocumentElementList(
            document_id=str(document.id),
            parse_run_id=str(run.id),
            items=[self._element_to_view(row) for row in rows],
        )

    def resolve_element_asset(self, element_id: int, city: CityContext) -> tuple[str, str]:
        """校验城市后返回图片元素的文件路径和媒体类型。"""
        element = self.parse_repository.get_element(element_id, city.id)
        if element is None or not element.asset_storage_key or not element.media_type:
            raise ResourceNotFoundError("文档图片元素不存在")
        return str(self.storage.resolve(element.asset_storage_key)), element.media_type

    def _execute_docx(
        self, document: SourceDocumentModel, run: DocumentParseRunModel, city: CityContext
    ) -> None:
        """解析DOCX并在同一事务中写入全部元素。"""
        suffix = Path(document.original_filename).suffix.lower()
        if suffix == ".doc":
            raise DocumentParseError(
                "legacy_doc_conversion_required",
                "旧版DOC需要先安装LibreOffice并转换为DOCX",
            )
        if suffix != ".docx":
            raise DocumentParseError(
                "parser_not_configured",
                f"当前尚未配置{suffix or '未知格式'}的确定性解析器",
            )
        source_path = self.storage.resolve(document.storage_key)
        parsed_elements = DocxParser().parse(source_path)
        if not parsed_elements:
            raise DocumentParseError("empty_document_content", "DOCX未提取到正文、表格或图片")
        for item in parsed_elements:
            asset_storage_key = None
            media_type = None
            if item.image is not None:
                stored_asset = self.storage.save_asset(
                    city_code=city.code,
                    document_id=document.id,
                    parse_run_id=run.id,
                    sequence_no=item.sequence_no,
                    source_name=item.image.source_name,
                    content=item.image.content,
                )
                asset_storage_key = stored_asset.storage_key
                media_type = stored_asset.media_type
            self.parse_repository.add_element(
                DocumentElementModel(
                    city_id=city.id,
                    document_id=document.id,
                    parse_run_id=run.id,
                    sequence_no=item.sequence_no,
                    element_type=item.element_type,
                    section_title=(item.section_title or "")[:200] or None,
                    content_text=item.content_text,
                    content_data=item.content_data,
                    asset_storage_key=asset_storage_key,
                    media_type=media_type,
                    source_locator=item.source_locator,
                )
            )
        run.element_count = len(parsed_elements)

    def _get_document(self, document_id: str, city: CityContext) -> SourceDocumentModel:
        """读取当前城市未归档材料。"""
        document = self.document_repository.get(document_id, city.id)
        if document is None:
            raise ResourceNotFoundError("材料不存在")
        return document

    def _parser_identity(self, filename: str) -> tuple[str, str]:
        """在运行创建前固定解析器名称和版本。"""
        suffix = Path(filename).suffix.lower()
        if suffix == ".docx":
            return DocxParser.name, DocxParser.version
        if suffix == ".doc":
            return "legacy_doc_converter", "not_configured"
        return "unconfigured_parser", "not_configured"

    def _run_to_view(self, run: DocumentParseRunModel, city_code: str) -> DocumentParseRunView:
        """生成稳定的解析运行响应。"""
        return DocumentParseRunView(
            id=str(run.id),
            document_id=str(run.document_id),
            city_code=city_code,
            run_no=run.run_no,
            status=run.status,
            parser_name=run.parser_name,
            parser_version=run.parser_version,
            element_count=run.element_count,
            error_code=run.error_code,
            error_message=run.error_message,
            created_by=str(run.created_by),
            created_at=run.created_at,
            started_at=run.started_at,
            finished_at=run.finished_at,
        )

    def _element_to_view(self, element: DocumentElementModel) -> DocumentElementView:
        """隐藏图片存储键，改为受城市上下文保护的资源地址。"""
        asset_url = None
        if element.asset_storage_key:
            asset_url = f"/api/v1/document-elements/{element.id}/content"
        return DocumentElementView(
            id=element.id,
            document_id=str(element.document_id),
            parse_run_id=str(element.parse_run_id),
            sequence_no=element.sequence_no,
            element_type=element.element_type,
            section_title=element.section_title,
            content_text=element.content_text,
            content_data=element.content_data,
            media_type=element.media_type,
            asset_url=asset_url,
            source_locator=element.source_locator,
        )
