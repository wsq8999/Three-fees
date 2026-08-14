"""新增本次待分析报告材料类型

Revision ID: c17f4e829a61
Revises: b960bdf1ab20
Create Date: 2026-07-31 20:30:00
"""

from collections.abc import Sequence

from alembic import op

revision: str = "c17f4e829a61"
down_revision: str | None = "b960bdf1ab20"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """允许任务上传包含正文、表格和图片的本次DOCX报告。"""
    op.drop_constraint(
        op.f("ck_source_document_document_type_values"),
        "source_document",
        schema="audit",
        type_="check",
    )
    op.create_check_constraint(
        op.f("ck_source_document_document_type_values"),
        "source_document",
        "document_type IN "
        "('historical_report', 'current_report', 'evidence_screenshot', 'report_template')",
        schema="audit",
    )


def downgrade() -> None:
    """仅在不存在本次报告记录时恢复旧材料类型约束。"""
    op.drop_constraint(
        op.f("ck_source_document_document_type_values"),
        "source_document",
        schema="audit",
        type_="check",
    )
    op.create_check_constraint(
        op.f("ck_source_document_document_type_values"),
        "source_document",
        "document_type IN ('historical_report', 'evidence_screenshot', 'report_template')",
        schema="audit",
    )
