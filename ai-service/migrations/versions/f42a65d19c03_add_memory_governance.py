"""增加统一记忆状态、标错与版本修订

Revision ID: f42a65d19c03
Revises: e831bc02d557
Create Date: 2026-08-03 20:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "f42a65d19c03"
down_revision: str | None = "e831bc02d557"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """分离生成状态和使用状态，并建立可恢复、可追溯的记忆治理结构。"""
    op.add_column(
        "audit_case",
        sa.Column("memory_status", sa.String(length=24), server_default="active", nullable=False),
        schema="audit",
    )
    op.create_check_constraint(
        op.f("ck_audit_case_memory_status_values"),
        "audit_case",
        "memory_status IN ('active', 'paused', 'invalidated')",
        schema="audit",
    )
    op.create_index(
        op.f("ix_audit_case_memory_status"),
        "audit_case",
        ["memory_status"],
        schema="audit",
    )

    op.add_column(
        "correction_memory",
        sa.Column("memory_status", sa.String(length=24), server_default="paused", nullable=False),
        schema="audit",
    )
    # 已经由人工确认的存量纠错仍应保持生效，其他流程状态默认暂停。
    op.execute(
        "UPDATE audit.correction_memory SET memory_status = 'active' WHERE status = 'confirmed'"
    )
    op.create_check_constraint(
        op.f("ck_correction_memory_memory_status_values"),
        "correction_memory",
        "memory_status IN ('active', 'paused', 'invalidated')",
        schema="audit",
    )
    op.create_index(
        op.f("ix_correction_memory_memory_status"),
        "correction_memory",
        ["memory_status"],
        schema="audit",
    )

    op.create_table(
        "memory_flag",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("city_id", sa.SmallInteger(), nullable=False),
        sa.Column("audit_case_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("correction_memory_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("flag_type", sa.String(length=32), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("status", sa.String(length=24), server_default="open", nullable=False),
        sa.Column("previous_memory_status", sa.String(length=24), nullable=False),
        sa.Column("reported_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "reported_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.Column("resolution_action", sa.String(length=24), nullable=True),
        sa.Column("resolution_note", sa.Text(), nullable=True),
        sa.Column("resolved_by", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "num_nonnulls(audit_case_id, correction_memory_id) = 1",
            name=op.f("ck_memory_flag_one_memory_target"),
        ),
        sa.CheckConstraint(
            "flag_type IN ('wrong_reason', 'wrong_site', 'wrong_evidence', "
            "'wrong_scope', 'duplicate', 'outdated', 'other')",
            name=op.f("ck_memory_flag_flag_type_values"),
        ),
        sa.CheckConstraint(
            "status IN ('open', 'resolved', 'dismissed')",
            name=op.f("ck_memory_flag_status_values"),
        ),
        sa.CheckConstraint(
            "previous_memory_status IN ('active', 'paused', 'invalidated')",
            name=op.f("ck_memory_flag_previous_memory_status_values"),
        ),
        sa.CheckConstraint(
            "resolution_action IS NULL OR resolution_action IN ('invalidated', 'restored')",
            name=op.f("ck_memory_flag_resolution_action_values"),
        ),
        sa.ForeignKeyConstraint(
            ["city_id"], ["audit.city.id"], name=op.f("fk_memory_flag_city_id_city")
        ),
        sa.ForeignKeyConstraint(
            ["reported_by"],
            ["audit.app_user.id"],
            name=op.f("fk_memory_flag_reported_by_app_user"),
        ),
        sa.ForeignKeyConstraint(
            ["resolved_by"],
            ["audit.app_user.id"],
            name=op.f("fk_memory_flag_resolved_by_app_user"),
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "audit_case_id"],
            ["audit.audit_case.city_id", "audit.audit_case.id"],
            name="fk_memory_flag_city_audit_case",
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "correction_memory_id"],
            ["audit.correction_memory.city_id", "audit.correction_memory.id"],
            name="fk_memory_flag_city_correction_memory",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_memory_flag")),
        schema="audit",
        comment="长期记忆的人工标错与复核记录",
    )

    op.create_table(
        "memory_revision",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("city_id", sa.SmallInteger(), nullable=False),
        sa.Column("audit_case_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("correction_memory_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("revision_no", sa.Integer(), nullable=False),
        sa.Column("snapshot", postgresql.JSONB(), nullable=False),
        sa.Column("change_reason", sa.Text(), nullable=False),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "num_nonnulls(audit_case_id, correction_memory_id) = 1",
            name=op.f("ck_memory_revision_one_memory_target"),
        ),
        sa.ForeignKeyConstraint(
            ["city_id"], ["audit.city.id"], name=op.f("fk_memory_revision_city_id_city")
        ),
        sa.ForeignKeyConstraint(
            ["created_by"],
            ["audit.app_user.id"],
            name=op.f("fk_memory_revision_created_by_app_user"),
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "audit_case_id"],
            ["audit.audit_case.city_id", "audit.audit_case.id"],
            name="fk_memory_revision_city_audit_case",
        ),
        sa.ForeignKeyConstraint(
            ["city_id", "correction_memory_id"],
            ["audit.correction_memory.city_id", "audit.correction_memory.id"],
            name="fk_memory_revision_city_correction_memory",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_memory_revision")),
        schema="audit",
        comment="长期记忆修改前的版本快照",
    )
    # 列表、目标历史和待复核查询均使用这些列，数据量增长后仍只扫描目标城市/记忆。
    for table, columns in {
        "memory_flag": ("city_id", "audit_case_id", "correction_memory_id", "flag_type", "status"),
        "memory_revision": ("city_id", "audit_case_id", "correction_memory_id"),
    }.items():
        for column in columns:
            op.create_index(op.f(f"ix_{table}_{column}"), table, [column], schema="audit")


def downgrade() -> None:
    """移除治理记录和统一状态；来源报告、案例和纠错内容保持不变。"""
    op.drop_table("memory_revision", schema="audit")
    op.drop_table("memory_flag", schema="audit")
    op.drop_index(
        op.f("ix_correction_memory_memory_status"),
        table_name="correction_memory",
        schema="audit",
    )
    op.drop_constraint(
        op.f("ck_correction_memory_memory_status_values"),
        "correction_memory",
        schema="audit",
        type_="check",
    )
    op.drop_column("correction_memory", "memory_status", schema="audit")
    op.drop_index(
        op.f("ix_audit_case_memory_status"),
        table_name="audit_case",
        schema="audit",
    )
    op.drop_constraint(
        op.f("ck_audit_case_memory_status_values"),
        "audit_case",
        schema="audit",
        type_="check",
    )
    op.drop_column("audit_case", "memory_status", schema="audit")
