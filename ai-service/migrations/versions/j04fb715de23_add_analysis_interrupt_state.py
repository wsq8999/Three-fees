"""增加Agent人工中断状态并创建检查点Schema

Revision ID: j04fb715de23
Revises: i93ea604cd12
Create Date: 2026-08-10 10:00:00

升级说明：业务表仅增加一个可空JSONB字段；LangGraph内部检查点表仍由官方Saver按版本创建。
回滚说明：先删除业务字段，再尝试删除空的langgraph Schema。这里刻意不使用CASCADE；
如果Schema内已有检查点，回滚会安全失败，运维人员必须先备份并明确清理检查点，避免误删可恢复任务。
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "j04fb715de23"
down_revision: str | None = "i93ea604cd12"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """创建独立检查点Schema，并保存当前待处理人工中断的安全展示数据。"""
    op.execute("CREATE SCHEMA IF NOT EXISTS langgraph AUTHORIZATION CURRENT_USER")
    op.add_column(
        "analysis_run",
        sa.Column(
            "pending_interrupt",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
            comment="当前等待人工输入的LangGraph中断信息；恢复或完成后清空",
        ),
        schema="audit",
    )


def downgrade() -> None:
    """删除业务字段；仅当检查点Schema为空时才删除Schema，禁止级联销毁。"""
    op.drop_column("analysis_run", "pending_interrupt", schema="audit")
    op.execute("DROP SCHEMA IF EXISTS langgraph")
