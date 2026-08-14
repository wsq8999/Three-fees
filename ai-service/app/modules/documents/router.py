from __future__ import annotations

"""材料管理REST路由。"""

from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, Query, Response, UploadFile, status
from fastapi.responses import FileResponse

from app.api.dependencies import CityContextDep, CurrentUserDep, DbSessionDep
from app.modules.documents.parse_service import DocumentParsingService
from app.modules.documents.schemas import (
    DocumentElementList,
    DocumentParseRunView,
    DocumentStatus,
    DocumentType,
    DocumentTypeList,
    DocumentTypeOption,
    SourceDocumentList,
    SourceDocumentUpdate,
    SourceDocumentView,
)
from app.modules.documents.service import SourceDocumentService
from app.modules.documents.storage import LocalDocumentStorage, get_document_storage

router = APIRouter(prefix="/documents", tags=["documents"])
document_types_router = APIRouter(prefix="/document-types", tags=["documents"])
parse_runs_router = APIRouter(prefix="/document-parse-runs", tags=["document-parsing"])
document_elements_router = APIRouter(prefix="/document-elements", tags=["document-parsing"])
DocumentStorageDep = Annotated[LocalDocumentStorage, Depends(get_document_storage)]


@document_types_router.get("", response_model=DocumentTypeList, summary="获取材料类型")
def list_document_types() -> DocumentTypeList:
    """固定类型由后端统一定义，避免前端复制业务枚举。"""
    common_extensions = [".doc", ".docx", ".pdf", ".png", ".jpg", ".jpeg", ".webp"]
    return DocumentTypeList(
        items=[
            DocumentTypeOption(
                code="historical_report",
                label="历史稽核报告",
                description="用于学习本城市、本报账点既往超标原因",
                allowed_extensions=common_extensions,
                requires_task=False,
                supports_site=True,
            ),
            DocumentTypeOption(
                code="current_report",
                label="本次待分析报告",
                description="当前任务上传的DOCX，正文、表格和图片都会参与AI分析",
                allowed_extensions=[".docx"],
                requires_task=True,
                supports_site=True,
            ),
            DocumentTypeOption(
                code="evidence_screenshot",
                label="本次稽核截图",
                description="当前稽核任务中等待AI分析的业务截图",
                allowed_extensions=[".png", ".jpg", ".jpeg", ".webp"],
                requires_task=True,
                supports_site=True,
            ),
            DocumentTypeOption(
                code="report_template",
                label="报告模板",
                description="生成稽核报告时遵循的城市模板",
                allowed_extensions=[".doc", ".docx"],
                requires_task=False,
                supports_site=False,
            ),
        ]
    )


@router.post("", response_model=SourceDocumentView, status_code=status.HTTP_201_CREATED)
def upload_document(
    response: Response,
    file: Annotated[UploadFile, File(description="DOC、DOCX、PDF或常见图片文件")],
    title: Annotated[str, Form(min_length=2, max_length=200)],
    document_type: Annotated[DocumentType, Form()],
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
    site_id: Annotated[str | None, Form()] = None,
    task_id: Annotated[str | None, Form()] = None,
) -> SourceDocumentView:
    """上传一份原始材料；本次报告必须以DOCX关联到具体任务。"""
    document = SourceDocumentService(session, storage).create(
        city=city,
        user=current_user,
        upload=file,
        document_type=document_type,
        title=title,
        site_id=site_id,
        task_id=task_id,
    )
    response.headers["Location"] = f"/api/v1/documents/{document.id}"
    return document


@router.get("", response_model=SourceDocumentList)
def list_documents(
    city: CityContextDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
    document_type: Annotated[DocumentType | None, Query()] = None,
    document_status: Annotated[DocumentStatus | None, Query(alias="status")] = None,
    site_id: Annotated[str | None, Query()] = None,
    keyword: Annotated[str | None, Query(min_length=1, max_length=200)] = None,
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 20,
) -> SourceDocumentList:
    """按城市、类型、状态、报账点和关键词分页查询材料。"""
    return SourceDocumentService(session, storage).list(
        city=city,
        document_type=document_type,
        status=document_status,
        site_id=site_id,
        keyword=keyword,
        page=page,
        page_size=page_size,
    )


@router.post(
    "/{document_id}/parse-runs",
    response_model=DocumentParseRunView,
    status_code=status.HTTP_201_CREATED,
)
def parse_document(
    document_id: str,
    response: Response,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> DocumentParseRunView:
    """创建并执行一次确定性解析，失败运行同样可查询。"""
    run = DocumentParsingService(session, storage).create_and_execute(
        document_id, city, current_user
    )
    response.headers["Location"] = f"/api/v1/document-parse-runs/{run.id}"
    return run


@router.get("/{document_id}/elements", response_model=DocumentElementList)
def list_document_elements(
    document_id: str,
    city: CityContextDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> DocumentElementList:
    """返回最近一次成功解析的全部顺序元素。"""
    return DocumentParsingService(session, storage).list_latest_elements(document_id, city)


@router.get("/{document_id}", response_model=SourceDocumentView)
def get_document(
    document_id: str,
    city: CityContextDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> SourceDocumentView:
    """获取当前城市材料详情。"""
    return SourceDocumentService(session, storage).get(document_id, city)


@router.patch("/{document_id}", response_model=SourceDocumentView)
def update_document(
    document_id: str,
    payload: SourceDocumentUpdate,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> SourceDocumentView:
    """更新历史报告的报账点关联，不修改或覆盖原文件。"""
    return SourceDocumentService(session, storage).update(
        document_id,
        city,
        current_user,
        payload,
    )


@router.get("/{document_id}/content", response_class=FileResponse)
def download_document(
    document_id: str,
    city: CityContextDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> FileResponse:
    """在城市隔离校验后下载原始文件。"""
    path, filename, media_type = SourceDocumentService(session, storage).resolve_content(
        document_id, city
    )
    return FileResponse(path=path, filename=filename, media_type=media_type)


@router.delete("/{document_id}", status_code=status.HTTP_204_NO_CONTENT)
def archive_document(
    document_id: str,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> Response:
    """软归档材料；原始文件和数据库记录均保留。"""
    SourceDocumentService(session, storage).archive(document_id, city, current_user)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@parse_runs_router.get("/{run_id}", response_model=DocumentParseRunView)
def get_document_parse_run(
    run_id: str,
    city: CityContextDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> DocumentParseRunView:
    """获取一次解析运行的完成状态和错误原因。"""
    return DocumentParsingService(session, storage).get_run(run_id, city)


@document_elements_router.get("/{element_id}/content", response_class=FileResponse)
def get_document_element_content(
    element_id: int,
    city: CityContextDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> FileResponse:
    """读取当前城市文档中提取出的图片元素。"""
    path, media_type = DocumentParsingService(session, storage).resolve_element_asset(
        element_id, city
    )
    return FileResponse(path=path, media_type=media_type)
