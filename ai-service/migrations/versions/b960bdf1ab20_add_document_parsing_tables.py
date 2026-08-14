"""新增文档解析运行、顺序元素和导入来源

Revision ID: b960bdf1ab20
Revises: a74f4972cf42
Create Date: 2026-07-31 17:01:21.559348
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "b960bdf1ab20"
down_revision: str | None = "a74f4972cf42"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """升级数据库结构。"""
    # 解析运行记录每次尝试，文档元素保存该次成功解析的有序内容。
    op.create_table(
        "document_parse_run",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("city_id", sa.SmallInteger(), nullable=False),
        sa.Column("document_id", sa.UUID(), nullable=False),
        sa.Column("run_no", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(length=24), nullable=False),
        sa.Column("parser_name", sa.String(length=64), nullable=False),
        sa.Column("parser_version", sa.String(length=32), nullable=False),
        sa.Column("element_count", sa.Integer(), server_default="0", nullable=False),
        sa.Column("error_code", sa.String(length=64), nullable=True),
        sa.Column("error_message", sa.String(length=500), nullable=True),
        sa.Column("created_by", sa.UUID(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "status IN ('queued', 'running', 'completed', 'failed')",
            name=op.f("ck_document_parse_run_status_values"),
        ),
        sa.CheckConstraint(
            "element_count >= 0", name=op.f("ck_document_parse_run_nonnegative_element_count")
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "document_id"],
            ["audit.source_document.city_id", "audit.source_document.id"],
            name="fk_document_parse_run_city_document",
        ),
        sa.ForeignKeyConstraint(
            ["city_id"], ["audit.city.id"], name=op.f("fk_document_parse_run_city_id_city")
        ),
        sa.ForeignKeyConstraint(
            ["created_by"],
            ["audit.app_user.id"],
            name=op.f("fk_document_parse_run_created_by_app_user"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_document_parse_run")),
        sa.UniqueConstraint("city_id", "document_id", "id", name="uq_document_parse_run_scope"),
        sa.UniqueConstraint("city_id", "id", name="uq_document_parse_run_city_id"),
        sa.UniqueConstraint("document_id", "run_no", name="uq_document_parse_run_no"),
        schema="audit",
        comment="原始材料解析运行",
    )
    op.create_index(
        op.f("ix_document_parse_run_city_id"),
        "document_parse_run",
        ["city_id"],
        unique=False,
        schema="audit",
    )
    op.create_index(
        op.f("ix_document_parse_run_document_id"),
        "document_parse_run",
        ["document_id"],
        unique=False,
        schema="audit",
    )
    op.create_index(
        op.f("ix_document_parse_run_status"),
        "document_parse_run",
        ["status"],
        unique=False,
        schema="audit",
    )
    op.create_table(
        "document_element",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=True), nullable=False),
        sa.Column("city_id", sa.SmallInteger(), nullable=False),
        sa.Column("document_id", sa.UUID(), nullable=False),
        sa.Column("parse_run_id", sa.UUID(), nullable=False),
        sa.Column("sequence_no", sa.Integer(), nullable=False),
        sa.Column("element_type", sa.String(length=24), nullable=False),
        sa.Column("section_title", sa.String(length=200), nullable=True),
        sa.Column("content_text", sa.Text(), nullable=True),
        sa.Column(
            "content_data",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("asset_storage_key", sa.String(length=500), nullable=True),
        sa.Column("media_type", sa.String(length=100), nullable=True),
        sa.Column(
            "source_locator",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "element_type <> 'image' OR asset_storage_key IS NOT NULL",
            name=op.f("ck_document_element_image_has_asset"),
        ),
        sa.CheckConstraint(
            "element_type IN ('heading', 'paragraph', 'table', 'image')",
            name=op.f("ck_document_element_element_type_values"),
        ),
        sa.CheckConstraint(
            "content_text IS NOT NULL OR asset_storage_key IS NOT NULL",
            name=op.f("ck_document_element_has_content"),
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "document_id", "parse_run_id"],
            [
                "audit.document_parse_run.city_id",
                "audit.document_parse_run.document_id",
                "audit.document_parse_run.id",
            ],
            name="fk_document_element_parse_scope",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_document_element")),
        sa.UniqueConstraint("parse_run_id", "sequence_no", name="uq_document_element_sequence"),
        schema="audit",
        comment="文档顺序元素和图片证据",
    )
    op.create_index(
        op.f("ix_document_element_city_id"),
        "document_element",
        ["city_id"],
        unique=False,
        schema="audit",
    )
    op.create_index(
        op.f("ix_document_element_document_id"),
        "document_element",
        ["document_id"],
        unique=False,
        schema="audit",
    )
    op.create_index(
        op.f("ix_document_element_element_type"),
        "document_element",
        ["element_type"],
        unique=False,
        schema="audit",
    )
    op.create_index(
        op.f("ix_document_element_parse_run_id"),
        "document_element",
        ["parse_run_id"],
        unique=False,
        schema="audit",
    )
    op.add_column(
        "source_document",
        sa.Column(
            "ingestion_method", sa.String(length=24), server_default="manual_upload", nullable=False
        ),
        schema="audit",
    )
    op.create_check_constraint(
        "ck_source_document_ingestion_method_values",
        "source_document",
        "ingestion_method IN ('manual_upload', 'batch_import')",
        schema="audit",
    )


def downgrade() -> None:
    """回滚数据库结构。"""
    # 先撤销材料来源字段，再按依赖顺序删除元素和运行表。
    op.drop_constraint(
        "ck_source_document_ingestion_method_values",
        "source_document",
        schema="audit",
        type_="check",
    )
    op.drop_column("source_document", "ingestion_method", schema="audit")
    op.drop_index(
        op.f("ix_document_element_parse_run_id"), table_name="document_element", schema="audit"
    )
    op.drop_index(
        op.f("ix_document_element_element_type"), table_name="document_element", schema="audit"
    )
    op.drop_index(
        op.f("ix_document_element_document_id"), table_name="document_element", schema="audit"
    )
    op.drop_index(
        op.f("ix_document_element_city_id"), table_name="document_element", schema="audit"
    )
    op.drop_table("document_element", schema="audit")
    op.drop_index(
        op.f("ix_document_parse_run_status"), table_name="document_parse_run", schema="audit"
    )
    op.drop_index(
        op.f("ix_document_parse_run_document_id"), table_name="document_parse_run", schema="audit"
    )
    op.drop_index(
        op.f("ix_document_parse_run_city_id"), table_name="document_parse_run", schema="audit"
    )
    op.drop_table("document_parse_run", schema="audit")
