from __future__ import annotations

"""稽核任务数据库模型。"""

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Integer,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class AuditTaskModel(Base):
    """一次稽核任务，可包含多次独立的Agent分析运行。"""

    __tablename__ = "audit_task"
    __table_args__ = (
        UniqueConstraint("city_id", "task_no", name="uq_audit_task_city_no"),
        UniqueConstraint("city_id", "id", name="uq_audit_task_city_id"),
        ForeignKeyConstraint(
            ["city_id", "site_id"],
            ["audit.site.city_id", "audit.site.id"],
            name="fk_audit_task_city_site",
        ),
        {"schema": "audit", "comment": "电费稽核任务"},
    )

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    city_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("audit.city.id"), nullable=False, index=True
    )
    site_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    task_no: Mapped[str] = mapped_column(String(40), nullable=False)
    audit_type: Mapped[str] = mapped_column(String(32), nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    question: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    created_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
