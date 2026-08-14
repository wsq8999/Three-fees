"""未生成成功的历史案例默认暂停

Revision ID: g71c84e2ab90
Revises: f42a65d19c03
Create Date: 2026-08-03 21:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "g71c84e2ab90"
down_revision: str | None = "f42a65d19c03"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """让pending/failed案例统一暂停，ready存量案例继续保持原使用状态。"""
    op.execute(
        "UPDATE audit.audit_case SET memory_status = 'paused' "
        "WHERE status <> 'ready' AND memory_status = 'active'"
    )
    op.alter_column(
        "audit_case",
        "memory_status",
        existing_type=sa.String(length=24),
        server_default="paused",
        existing_nullable=False,
        schema="audit",
    )


def downgrade() -> None:
    """恢复旧默认值；不擅自改变业务员已经操作过的具体记忆状态。"""
    op.alter_column(
        "audit_case",
        "memory_status",
        existing_type=sa.String(length=24),
        server_default="active",
        existing_nullable=False,
        schema="audit",
    )
