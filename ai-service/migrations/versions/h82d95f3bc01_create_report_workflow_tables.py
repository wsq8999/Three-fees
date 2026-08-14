"""创建报告审核和版本表

Revision ID: h82d95f3bc01
Revises: g71c84e2ab90
Create Date: 2026-08-03 15:30:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "h82d95f3bc01"
down_revision: str | None = "g71c84e2ab90"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """建立报告主记录和只追加版本，并以复合外键落实城市隔离。"""
    op.create_table(
        "report",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("city_id", sa.SmallInteger(), nullable=False),
        sa.Column("site_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("task_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("analysis_run_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(length=24), nullable=False),
        sa.Column("current_version", sa.Integer(), server_default="1", nullable=False),
        sa.Column("approved_version", sa.Integer(), nullable=True),
        sa.Column("review_note", sa.Text(), nullable=True),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("reviewed_by", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.Column("reviewed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "status IN ('draft', 'in_review', 'returned', 'approved')",
            name="status_values",
        ),
        sa.CheckConstraint("current_version > 0", name="positive_current_version"),
        sa.CheckConstraint(
            "approved_version IS NULL OR approved_version > 0",
            name="positive_approved_version",
        ),
        sa.ForeignKeyConstraint(["city_id"], ["audit.city.id"]),
        sa.ForeignKeyConstraint(["created_by"], ["audit.app_user.id"]),
        sa.ForeignKeyConstraint(["reviewed_by"], ["audit.app_user.id"]),
        sa.ForeignKeyConstraint(
            ["city_id", "site_id"],
            ["audit.site.city_id", "audit.site.id"],
            name="fk_report_city_site",
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "task_id"],
            ["audit.audit_task.city_id", "audit.audit_task.id"],
            name="fk_report_city_task",
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "analysis_run_id"],
            ["audit.analysis_run.city_id", "audit.analysis_run.id"],
            name="fk_report_city_analysis_run",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("analysis_run_id", name="uq_report_analysis_run"),
        sa.UniqueConstraint("city_id", "id", name="uq_report_city_id"),
        schema="audit",
        comment="可编辑和审核的稽核报告",
    )
    op.create_index("ix_audit_report_city_id", "report", ["city_id"], schema="audit")
    op.create_index("ix_audit_report_site_id", "report", ["site_id"], schema="audit")
    op.create_index("ix_audit_report_task_id", "report", ["task_id"], schema="audit")
    op.create_index(
        "ix_audit_report_analysis_run_id", "report", ["analysis_run_id"], schema="audit"
    )
    op.create_index("ix_audit_report_status", "report", ["status"], schema="audit")

    op.create_table(
        "report_version",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("city_id", sa.SmallInteger(), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("version_no", sa.Integer(), nullable=False),
        sa.Column("title", sa.String(length=200), nullable=False),
        sa.Column("sections", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column(
            "uncertain_items",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "review_reasons",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("change_summary", sa.Text(), nullable=False),
        sa.Column("source_document_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("docx_storage_key", sa.String(length=500), nullable=True),
        sa.Column("docx_sha256", sa.String(length=64), nullable=True),
        sa.Column("docx_size_bytes", sa.Integer(), nullable=True),
        sa.Column("generated_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.CheckConstraint("version_no > 0", name="positive_version_no"),
        sa.CheckConstraint(
            "docx_size_bytes IS NULL OR docx_size_bytes > 0", name="positive_docx_size"
        ),
        sa.ForeignKeyConstraint(["created_by"], ["audit.app_user.id"]),
        sa.ForeignKeyConstraint(
            ["city_id", "report_id"],
            ["audit.report.city_id", "audit.report.id"],
            name="fk_report_version_city_report",
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "source_document_id"],
            ["audit.source_document.city_id", "audit.source_document.id"],
            name="fk_report_version_city_source_document",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("report_id", "version_no", name="uq_report_version_no"),
        sa.UniqueConstraint("city_id", "id", name="uq_report_version_city_id"),
        sa.UniqueConstraint("docx_storage_key"),
        schema="audit",
        comment="稽核报告不可覆盖版本",
    )
    op.create_index(
        "ix_audit_report_version_city_id", "report_version", ["city_id"], schema="audit"
    )
    op.create_index(
        "ix_audit_report_version_report_id", "report_version", ["report_id"], schema="audit"
    )


def downgrade() -> None:
    """按依赖顺序移除报告版本和报告主表。"""
    op.drop_index("ix_audit_report_version_report_id", table_name="report_version", schema="audit")
    op.drop_index("ix_audit_report_version_city_id", table_name="report_version", schema="audit")
    op.drop_table("report_version", schema="audit")
    op.drop_index("ix_audit_report_status", table_name="report", schema="audit")
    op.drop_index("ix_audit_report_analysis_run_id", table_name="report", schema="audit")
    op.drop_index("ix_audit_report_task_id", table_name="report", schema="audit")
    op.drop_index("ix_audit_report_site_id", table_name="report", schema="audit")
    op.drop_index("ix_audit_report_city_id", table_name="report", schema="audit")
    op.drop_table("report", schema="audit")
