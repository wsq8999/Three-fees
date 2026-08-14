"""创建人工纠错记忆表

Revision ID: e831bc02d557
Revises: d24c91fa6e10
Create Date: 2026-08-03 16:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "e831bc02d557"
down_revision: str | None = "d24c91fa6e10"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """新增纠错原话、结构化理解、确认状态和来源运行的完整审计记录。"""
    op.create_table(
        "correction_memory",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("city_id", sa.SmallInteger(), nullable=False),
        sa.Column("site_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("task_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("analysis_run_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(length=24), server_default="draft", nullable=False),
        sa.Column("original_reason", sa.Text(), nullable=True),
        sa.Column("original_reason_category", sa.String(length=100), nullable=True),
        sa.Column(
            "original_judgment",
            postgresql.JSONB(),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("user_message", sa.Text(), nullable=False),
        sa.Column("corrected_reason", sa.Text(), nullable=False),
        sa.Column("reason_category", sa.String(length=100), nullable=False),
        sa.Column(
            "applicability_conditions",
            postgresql.JSONB(),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "supporting_element_ids",
            postgresql.JSONB(),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("interpretation_summary", sa.Text(), nullable=False),
        sa.Column(
            "uncertain_items",
            postgresql.JSONB(),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("confidence", sa.Numeric(precision=4, scale=3), nullable=False),
        sa.Column("model_name", sa.String(length=100), nullable=False),
        sa.Column("prompt_version", sa.String(length=64), nullable=False),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("confirmed_by", postgresql.UUID(as_uuid=True), nullable=True),
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
        sa.Column("confirmed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "confidence >= 0 AND confidence <= 1",
            name=op.f("ck_correction_memory_confidence_range"),
        ),
        sa.CheckConstraint(
            "status IN ('draft', 'confirmed', 'rejected', 'archived')",
            name=op.f("ck_correction_memory_status_values"),
        ),
        sa.ForeignKeyConstraint(
            ["city_id"],
            ["audit.city.id"],
            name=op.f("fk_correction_memory_city_id_city"),
        ),
        sa.ForeignKeyConstraint(
            ["created_by"],
            ["audit.app_user.id"],
            name=op.f("fk_correction_memory_created_by_app_user"),
        ),
        sa.ForeignKeyConstraint(
            ["confirmed_by"],
            ["audit.app_user.id"],
            name=op.f("fk_correction_memory_confirmed_by_app_user"),
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "site_id"],
            ["audit.site.city_id", "audit.site.id"],
            name="fk_correction_memory_city_site",
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "task_id"],
            ["audit.audit_task.city_id", "audit.audit_task.id"],
            name="fk_correction_memory_city_task",
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "analysis_run_id"],
            ["audit.analysis_run.city_id", "audit.analysis_run.id"],
            name="fk_correction_memory_city_run",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_correction_memory")),
        sa.UniqueConstraint("city_id", "id", name="uq_correction_memory_city_id"),
        sa.UniqueConstraint("analysis_run_id", name="uq_correction_memory_analysis_run"),
        schema="audit",
        comment="业务员确认的AI纠错记忆",
    )
    # 高频查询均包含城市，再按报账点、状态或来源运行缩小范围。
    for column in (
        "city_id",
        "site_id",
        "task_id",
        "analysis_run_id",
        "status",
        "reason_category",
    ):
        op.create_index(
            op.f(f"ix_correction_memory_{column}"),
            "correction_memory",
            [column],
            unique=False,
            schema="audit",
        )


def downgrade() -> None:
    """删除纠错记忆表，不修改来源任务、运行和原始报告。"""
    op.drop_table("correction_memory", schema="audit")
