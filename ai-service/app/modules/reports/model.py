from __future__ import annotations

"""稽核报告及不可覆盖版本的数据库模型。"""

from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
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


class ReportModel(Base):
    """一份由分析草稿进入人工审核流程的业务报告。"""

    __tablename__ = "report"
    __table_args__ = (
        UniqueConstraint("analysis_run_id", name="uq_report_analysis_run"),
        UniqueConstraint("city_id", "id", name="uq_report_city_id"),
        ForeignKeyConstraint(
            ["city_id", "site_id"],
            ["audit.site.city_id", "audit.site.id"],
            name="fk_report_city_site",
        ),
        ForeignKeyConstraint(
            ["city_id", "task_id"],
            ["audit.audit_task.city_id", "audit.audit_task.id"],
            name="fk_report_city_task",
        ),
        ForeignKeyConstraint(
            ["city_id", "analysis_run_id"],
            ["audit.analysis_run.city_id", "audit.analysis_run.id"],
            name="fk_report_city_analysis_run",
        ),
        CheckConstraint(
            "status IN ('draft', 'in_review', 'returned', 'approved')",
            name="status_values",
        ),
        CheckConstraint("current_version > 0", name="positive_current_version"),
        CheckConstraint(
            "approved_version IS NULL OR approved_version > 0",
            name="positive_approved_version",
        ),
        {"schema": "audit", "comment": "可编辑和审核的稽核报告"},
    )

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    city_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("audit.city.id"), nullable=False, index=True
    )
    site_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    task_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    analysis_run_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    status: Mapped[str] = mapped_column(String(24), nullable=False, index=True)
    current_version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    approved_version: Mapped[int | None] = mapped_column(Integer)
    review_note: Mapped[str | None] = mapped_column(Text)
    created_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    reviewed_by: Mapped[UUID | None] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id")
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class ReportVersionModel(Base):
    """报告的只追加内容快照，旧版本永远不会被原地覆盖。"""

    __tablename__ = "report_version"
    __table_args__ = (
        UniqueConstraint("report_id", "version_no", name="uq_report_version_no"),
        UniqueConstraint("city_id", "id", name="uq_report_version_city_id"),
        ForeignKeyConstraint(
            ["city_id", "report_id"],
            ["audit.report.city_id", "audit.report.id"],
            name="fk_report_version_city_report",
        ),
        ForeignKeyConstraint(
            ["city_id", "source_document_id"],
            ["audit.source_document.city_id", "audit.source_document.id"],
            name="fk_report_version_city_source_document",
        ),
        CheckConstraint("version_no > 0", name="positive_version_no"),
        CheckConstraint(
            "docx_size_bytes IS NULL OR docx_size_bytes > 0", name="positive_docx_size"
        ),
        {"schema": "audit", "comment": "稽核报告不可覆盖版本"},
    )

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    city_id: Mapped[int] = mapped_column(SmallInteger, nullable=False, index=True)
    report_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    version_no: Mapped[int] = mapped_column(Integer, nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    sections: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False)
    uncertain_items: Mapped[list[str]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    review_reasons: Mapped[list[str]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    change_summary: Mapped[str] = mapped_column(Text, nullable=False)
    source_document_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False)
    docx_storage_key: Mapped[str | None] = mapped_column(String(500), unique=True)
    docx_sha256: Mapped[str | None] = mapped_column(String(64))
    docx_size_bytes: Mapped[int | None] = mapped_column(Integer)
    generated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
