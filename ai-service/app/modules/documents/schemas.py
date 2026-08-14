from __future__ import annotations

"""材料管理REST契约。"""

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel

DocumentType = Literal[
    "historical_report",
    "current_report",
    "evidence_screenshot",
    "report_template",
]
DocumentStatus = Literal["uploaded", "parsing", "parsed", "failed", "archived"]
IngestionMethod = Literal["manual_upload", "batch_import"]
ParseRunStatus = Literal["queued", "running", "completed", "failed"]
DocumentElementType = Literal["heading", "paragraph", "table", "image"]


class SourceDocumentView(BaseModel):
    """向前端暴露业务元数据，不泄漏服务器物理路径。"""

    id: str
    city_code: str
    site_id: str | None
    task_id: str | None
    document_type: DocumentType
    title: str
    original_filename: str
    media_type: str
    size_bytes: int
    sha256: str
    ingestion_method: IngestionMethod
    status: DocumentStatus
    download_url: str
    created_by: str
    created_at: datetime
    updated_at: datetime
    archived_at: datetime | None


class SourceDocumentList(BaseModel):
    """带总数的分页材料列表。"""

    items: list[SourceDocumentView]
    total: int
    page: int
    page_size: int


class SourceDocumentUpdate(BaseModel):
    """当前只开放历史报告的报账点关联，避免无边界修改文件元数据。"""

    site_id: str | None


class DocumentTypeOption(BaseModel):
    """由后端统一提供的材料类型下拉选项。"""

    code: DocumentType
    label: str
    description: str
    allowed_extensions: list[str]
    requires_task: bool
    supports_site: bool


class DocumentTypeList(BaseModel):
    """固定材料类型集合。"""

    items: list[DocumentTypeOption]


class DocumentParseRunView(BaseModel):
    """一次解析尝试的状态、版本、数量和失败原因。"""

    id: str
    document_id: str
    city_code: str
    run_no: int
    status: ParseRunStatus
    parser_name: str
    parser_version: str
    element_count: int
    error_code: str | None
    error_message: str | None
    created_by: str
    created_at: datetime
    started_at: datetime | None
    finished_at: datetime | None


class DocumentElementView(BaseModel):
    """前端可审阅的顺序化文档元素。"""

    id: int
    document_id: str
    parse_run_id: str
    sequence_no: int
    element_type: DocumentElementType
    section_title: str | None
    content_text: str | None
    content_data: dict[str, Any]
    media_type: str | None
    asset_url: str | None
    source_locator: dict[str, Any]


class DocumentElementList(BaseModel):
    """一次成功解析产生的全部顺序元素。"""

    document_id: str
    parse_run_id: str
    items: list[DocumentElementView]
