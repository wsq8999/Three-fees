"""新增原始材料表

Revision ID: a74f4972cf42
Revises: 3f35f0ced3ba
Create Date: 2026-07-31 16:30:20.345596
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "a74f4972cf42"
down_revision: str | None = "3f35f0ced3ba"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """升级数据库结构。"""
    # 材料业务记录与原文件一对一，后续解析结果使用独立迁移扩展。
    op.create_table(
        "source_document",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("city_id", sa.SmallInteger(), nullable=False),
        sa.Column("site_id", sa.UUID(), nullable=True),
        sa.Column("task_id", sa.UUID(), nullable=True),
        sa.Column("document_type", sa.String(length=32), nullable=False),
        sa.Column("title", sa.String(length=200), nullable=False),
        sa.Column("original_filename", sa.String(length=255), nullable=False),
        sa.Column("media_type", sa.String(length=100), nullable=False),
        sa.Column("size_bytes", sa.BigInteger(), nullable=False),
        sa.Column("sha256", sa.String(length=64), nullable=False),
        sa.Column("storage_key", sa.String(length=500), nullable=False),
        sa.Column("status", sa.String(length=24), server_default="uploaded", nullable=False),
        sa.Column("created_by", sa.UUID(), nullable=False),
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
        sa.Column("archived_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "document_type IN ('historical_report', 'evidence_screenshot', 'report_template')",
            name=op.f("ck_source_document_document_type_values"),
        ),
        sa.CheckConstraint(
            "status IN ('uploaded', 'parsing', 'parsed', 'failed', 'archived')",
            name=op.f("ck_source_document_status_values"),
        ),
        sa.CheckConstraint("size_bytes > 0", name=op.f("ck_source_document_positive_size")),
        sa.ForeignKeyConstraint(
            ["city_id", "site_id"],
            ["audit.site.city_id", "audit.site.id"],
            name="fk_source_document_city_site",
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "task_id"],
            ["audit.audit_task.city_id", "audit.audit_task.id"],
            name="fk_source_document_city_task",
        ),
        sa.ForeignKeyConstraint(
            ["city_id"], ["audit.city.id"], name=op.f("fk_source_document_city_id_city")
        ),
        sa.ForeignKeyConstraint(
            ["created_by"],
            ["audit.app_user.id"],
            name=op.f("fk_source_document_created_by_app_user"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_source_document")),
        sa.UniqueConstraint("city_id", "id", name="uq_source_document_city_id"),
        sa.UniqueConstraint("storage_key", name=op.f("uq_source_document_storage_key")),
        schema="audit",
        comment="原始稽核材料及文件元数据",
    )
    op.create_index(
        op.f("ix_source_document_city_id"),
        "source_document",
        ["city_id"],
        unique=False,
        schema="audit",
    )
    op.create_index(
        op.f("ix_source_document_document_type"),
        "source_document",
        ["document_type"],
        unique=False,
        schema="audit",
    )
    op.create_index(
        op.f("ix_source_document_sha256"),
        "source_document",
        ["sha256"],
        unique=False,
        schema="audit",
    )
    op.create_index(
        op.f("ix_source_document_site_id"),
        "source_document",
        ["site_id"],
        unique=False,
        schema="audit",
    )
    op.create_index(
        op.f("ix_source_document_task_id"),
        "source_document",
        ["task_id"],
        unique=False,
        schema="audit",
    )


def downgrade() -> None:
    """回滚数据库结构。"""
    # 先删除索引再删除材料表，回滚不影响此前的核心业务表。
    op.drop_index(op.f("ix_source_document_task_id"), table_name="source_document", schema="audit")
    op.drop_index(op.f("ix_source_document_site_id"), table_name="source_document", schema="audit")
    op.drop_index(op.f("ix_source_document_sha256"), table_name="source_document", schema="audit")
    op.drop_index(
        op.f("ix_source_document_document_type"), table_name="source_document", schema="audit"
    )
    op.drop_index(op.f("ix_source_document_city_id"), table_name="source_document", schema="audit")
    op.drop_table("source_document", schema="audit")
