"""统一报告表索引命名

Revision ID: i93ea604cd12
Revises: h82d95f3bc01
Create Date: 2026-08-03 15:50:00
"""

from collections.abc import Sequence

from alembic import op

revision: str = "i93ea604cd12"
down_revision: str | None = "h82d95f3bc01"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

INDEX_RENAMES = {
    "ix_audit_report_analysis_run_id": "ix_report_analysis_run_id",
    "ix_audit_report_city_id": "ix_report_city_id",
    "ix_audit_report_site_id": "ix_report_site_id",
    "ix_audit_report_status": "ix_report_status",
    "ix_audit_report_task_id": "ix_report_task_id",
    "ix_audit_report_version_city_id": "ix_report_version_city_id",
    "ix_audit_report_version_report_id": "ix_report_version_report_id",
}


def upgrade() -> None:
    """按SQLAlchemy默认规则重命名索引，不触碰表内数据。"""
    for old_name, new_name in INDEX_RENAMES.items():
        op.execute(f'ALTER INDEX audit."{old_name}" RENAME TO "{new_name}"')


def downgrade() -> None:
    """恢复初始迁移中的索引名称。"""
    for old_name, new_name in INDEX_RENAMES.items():
        op.execute(f'ALTER INDEX audit."{new_name}" RENAME TO "{old_name}"')
