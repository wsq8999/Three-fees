from __future__ import annotations

"""记忆标错与版本留痕数据库模型。"""

from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Integer,
    SmallInteger,
    String,
    Text,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class MemoryFlagModel(Base):
    """用户对任意一条长期记忆发起的独立标错记录。

    标错与“大白话纠正一次AI结果”是两个流程：本表直接作用于既有长期记忆。
    创建标错时目标会立即暂停，复核后可以恢复，也可以正式判定为无效。
    """

    __tablename__ = "memory_flag"
    __table_args__ = (
        ForeignKeyConstraint(
            ["city_id", "audit_case_id"],
            ["audit.audit_case.city_id", "audit.audit_case.id"],
            name="fk_memory_flag_city_audit_case",
        ),
        ForeignKeyConstraint(
            ["city_id", "correction_memory_id"],
            ["audit.correction_memory.city_id", "audit.correction_memory.id"],
            name="fk_memory_flag_city_correction_memory",
        ),
        # 一条标错只能指向一种记忆，数据库层阻止目标为空或同时指向两张表。
        CheckConstraint(
            "num_nonnulls(audit_case_id, correction_memory_id) = 1",
            name="one_memory_target",
        ),
        CheckConstraint(
            "flag_type IN ('wrong_reason', 'wrong_site', 'wrong_evidence', "
            "'wrong_scope', 'duplicate', 'outdated', 'other')",
            name="flag_type_values",
        ),
        CheckConstraint(
            "status IN ('open', 'resolved', 'dismissed')",
            name="status_values",
        ),
        CheckConstraint(
            "previous_memory_status IN ('active', 'paused', 'invalidated')",
            name="previous_memory_status_values",
        ),
        CheckConstraint(
            "resolution_action IS NULL OR resolution_action IN ('invalidated', 'restored')",
            name="resolution_action_values",
        ),
        {"schema": "audit", "comment": "长期记忆的人工标错与复核记录"},
    )

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    # 城市放入复合外键，确保苏州请求不可能标错南京记忆。
    city_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("audit.city.id"), nullable=False, index=True
    )
    audit_case_id: Mapped[UUID | None] = mapped_column(PGUUID(as_uuid=True), index=True)
    correction_memory_id: Mapped[UUID | None] = mapped_column(PGUUID(as_uuid=True), index=True)
    flag_type: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    description: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(
        String(24), nullable=False, server_default="open", index=True
    )
    # 误标恢复时回到发起标错前的状态，而不是一律强行设为active。
    previous_memory_status: Mapped[str] = mapped_column(String(24), nullable=False)
    reported_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    reported_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    resolution_action: Mapped[str | None] = mapped_column(String(24))
    resolution_note: Mapped[str | None] = mapped_column(Text)
    resolved_by: Mapped[UUID | None] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id")
    )
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class MemoryRevisionModel(Base):
    """错误记忆被修正前的完整快照。

    页面上的“修改并重新启用”会更新稳定的主记忆ID，但旧内容不会消失，而是先写入
    本表。这样已有分析运行仍可追溯当时使用的版本，管理人员也能审计修改前后差异。
    """

    __tablename__ = "memory_revision"
    __table_args__ = (
        ForeignKeyConstraint(
            ["city_id", "audit_case_id"],
            ["audit.audit_case.city_id", "audit.audit_case.id"],
            name="fk_memory_revision_city_audit_case",
        ),
        ForeignKeyConstraint(
            ["city_id", "correction_memory_id"],
            ["audit.correction_memory.city_id", "audit.correction_memory.id"],
            name="fk_memory_revision_city_correction_memory",
        ),
        CheckConstraint(
            "num_nonnulls(audit_case_id, correction_memory_id) = 1",
            name="one_memory_target",
        ),
        {"schema": "audit", "comment": "长期记忆修改前的版本快照"},
    )

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    city_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("audit.city.id"), nullable=False, index=True
    )
    audit_case_id: Mapped[UUID | None] = mapped_column(PGUUID(as_uuid=True), index=True)
    correction_memory_id: Mapped[UUID | None] = mapped_column(PGUUID(as_uuid=True), index=True)
    # revision_no记录被替换的版本号；主表更新成功后版本号加一。
    revision_no: Mapped[int] = mapped_column(Integer, nullable=False)
    snapshot: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    change_reason: Mapped[str] = mapped_column(Text, nullable=False)
    created_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
