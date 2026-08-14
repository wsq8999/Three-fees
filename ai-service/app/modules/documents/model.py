from __future__ import annotations

"""原始材料数据库模型。"""

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Identity,
    Integer,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class SourceDocumentModel(Base):
    """一份用户可识别的原始材料及其可追溯文件信息。"""

    __tablename__ = "source_document"
    __table_args__ = (
        UniqueConstraint("city_id", "id", name="uq_source_document_city_id"),
        ForeignKeyConstraint(
            ["city_id", "site_id"],
            ["audit.site.city_id", "audit.site.id"],
            name="fk_source_document_city_site",
        ),
        ForeignKeyConstraint(
            ["city_id", "task_id"],
            ["audit.audit_task.city_id", "audit.audit_task.id"],
            name="fk_source_document_city_task",
        ),
        CheckConstraint(
            "document_type IN "
            "('historical_report', 'current_report', 'evidence_screenshot', 'report_template')",
            name="document_type_values",
        ),
        CheckConstraint(
            "status IN ('uploaded', 'parsing', 'parsed', 'failed', 'archived')",
            name="status_values",
        ),
        CheckConstraint("size_bytes > 0", name="positive_size"),
        CheckConstraint(
            "ingestion_method IN ('manual_upload', 'batch_import')",
            name="ingestion_method_values",
        ),
        {"schema": "audit", "comment": "原始稽核材料及文件元数据"},
    )

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    city_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("audit.city.id"), nullable=False, index=True
    )
    site_id: Mapped[UUID | None] = mapped_column(PGUUID(as_uuid=True), index=True)
    task_id: Mapped[UUID | None] = mapped_column(PGUUID(as_uuid=True), index=True)
    document_type: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    original_filename: Mapped[str] = mapped_column(String(255), nullable=False)
    media_type: Mapped[str] = mapped_column(String(100), nullable=False)
    size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    sha256: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    storage_key: Mapped[str] = mapped_column(String(500), nullable=False, unique=True)
    ingestion_method: Mapped[str] = mapped_column(
        String(24), nullable=False, server_default="manual_upload"
    )
    status: Mapped[str] = mapped_column(String(24), nullable=False, server_default="uploaded")
    created_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    archived_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class DocumentParseRunModel(Base):
    """一次可审计的确定性文档解析尝试。"""

    __tablename__ = "document_parse_run"
    __table_args__ = (
        UniqueConstraint("document_id", "run_no", name="uq_document_parse_run_no"),
        UniqueConstraint("city_id", "id", name="uq_document_parse_run_city_id"),
        UniqueConstraint("city_id", "document_id", "id", name="uq_document_parse_run_scope"),
        ForeignKeyConstraint(
            ["city_id", "document_id"],
            ["audit.source_document.city_id", "audit.source_document.id"],
            name="fk_document_parse_run_city_document",
        ),
        CheckConstraint(
            "status IN ('queued', 'running', 'completed', 'failed')",
            name="status_values",
        ),
        CheckConstraint("element_count >= 0", name="nonnegative_element_count"),
        {"schema": "audit", "comment": "原始材料解析运行"},
    )

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    city_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("audit.city.id"), nullable=False, index=True
    )
    document_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    run_no: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(24), nullable=False, index=True)
    parser_name: Mapped[str] = mapped_column(String(64), nullable=False)
    parser_version: Mapped[str] = mapped_column(String(32), nullable=False)
    element_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    error_code: Mapped[str | None] = mapped_column(String(64))
    error_message: Mapped[str | None] = mapped_column(String(500))
    created_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class DocumentElementModel(Base):
    """按原文顺序保存段落、表格和图片，作为后续AI证据定位基础。"""

    __tablename__ = "document_element"
    __table_args__ = (
        UniqueConstraint("parse_run_id", "sequence_no", name="uq_document_element_sequence"),
        ForeignKeyConstraint(
            ["city_id", "document_id", "parse_run_id"],
            [
                "audit.document_parse_run.city_id",
                "audit.document_parse_run.document_id",
                "audit.document_parse_run.id",
            ],
            name="fk_document_element_parse_scope",
        ),
        CheckConstraint(
            "element_type IN ('heading', 'paragraph', 'table', 'image')",
            name="element_type_values",
        ),
        CheckConstraint(
            "content_text IS NOT NULL OR asset_storage_key IS NOT NULL",
            name="has_content",
        ),
        CheckConstraint(
            "element_type <> 'image' OR asset_storage_key IS NOT NULL",
            name="image_has_asset",
        ),
        {"schema": "audit", "comment": "文档顺序元素和图片证据"},
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=True), primary_key=True)
    city_id: Mapped[int] = mapped_column(SmallInteger, nullable=False, index=True)
    document_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    parse_run_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    sequence_no: Mapped[int] = mapped_column(Integer, nullable=False)
    element_type: Mapped[str] = mapped_column(String(24), nullable=False, index=True)
    section_title: Mapped[str | None] = mapped_column(String(200))
    content_text: Mapped[str | None] = mapped_column(Text)
    content_data: Mapped[dict[str, object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    asset_storage_key: Mapped[str | None] = mapped_column(String(500))
    media_type: Mapped[str | None] = mapped_column(String(100))
    source_locator: Mapped[dict[str, object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
