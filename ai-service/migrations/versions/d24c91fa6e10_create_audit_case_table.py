"""创建历史稽核案例表

Revision ID: d24c91fa6e10
Revises: c17f4e829a61
Create Date: 2026-08-03 10:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "d24c91fa6e10"
down_revision: str | None = "c17f4e829a61"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """新增每份历史报告唯一对应的结构化案例记忆。"""
    op.create_table(
        "audit_case",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("city_id", sa.SmallInteger(), nullable=False),
        sa.Column("site_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("source_document_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(length=24), server_default="pending", nullable=False),
        sa.Column("billing_period", sa.String(length=100), nullable=True),
        sa.Column(
            "over_limit_items",
            postgresql.JSONB(),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("primary_reason", sa.Text(), nullable=True),
        sa.Column("reason_category", sa.String(length=100), nullable=True),
        sa.Column(
            "key_facts", postgresql.JSONB(), server_default=sa.text("'[]'::jsonb"), nullable=False
        ),
        sa.Column(
            "evidence_element_ids",
            postgresql.JSONB(),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "uncertain_items",
            postgresql.JSONB(),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("confidence", sa.Numeric(precision=4, scale=3), nullable=True),
        sa.Column("model_name", sa.String(length=100), nullable=True),
        sa.Column("prompt_version", sa.String(length=64), nullable=True),
        sa.Column("error_code", sa.String(length=64), nullable=True),
        sa.Column("error_message", sa.String(length=500), nullable=True),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("version", sa.Integer(), server_default="1", nullable=False),
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
        sa.Column("analyzed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "confidence IS NULL OR (confidence >= 0 AND confidence <= 1)",
            name=op.f("ck_audit_case_confidence_range"),
        ),
        sa.CheckConstraint(
            "status IN ('pending', 'ready', 'failed', 'archived')",
            name=op.f("ck_audit_case_status_values"),
        ),
        sa.ForeignKeyConstraint(
            ["city_id"], ["audit.city.id"], name=op.f("fk_audit_case_city_id_city")
        ),
        sa.ForeignKeyConstraint(
            ["created_by"], ["audit.app_user.id"], name=op.f("fk_audit_case_created_by_app_user")
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "site_id"],
            ["audit.site.city_id", "audit.site.id"],
            name="fk_audit_case_city_site",
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "source_document_id"],
            ["audit.source_document.city_id", "audit.source_document.id"],
            name="fk_audit_case_city_document",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_audit_case")),
        sa.UniqueConstraint("city_id", "id", name="uq_audit_case_city_id"),
        sa.UniqueConstraint("source_document_id", name="uq_audit_case_source_document"),
        schema="audit",
        comment="历史报告结构化案例记忆",
    )
    for column in ("city_id", "site_id", "source_document_id", "status", "reason_category"):
        op.create_index(
            op.f(f"ix_audit_case_{column}"), "audit_case", [column], unique=False, schema="audit"
        )


def downgrade() -> None:
    """删除历史案例表，不触碰原始历史报告。"""
    op.drop_table("audit_case", schema="audit")
