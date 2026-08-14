from __future__ import annotations

"""Agent分析运行及节点事件数据库模型。"""

from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Identity,
    Integer,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class AnalysisRunModel(Base):
    """一次可追踪、可恢复的LangGraph运行。"""

    __tablename__ = "analysis_run"
    __table_args__ = (
        UniqueConstraint("task_id", "run_no", name="uq_analysis_run_task_no"),
        UniqueConstraint("city_id", "id", name="uq_analysis_run_city_id"),
        CheckConstraint("progress BETWEEN 0 AND 100", name="progress_range"),
        ForeignKeyConstraint(
            ["city_id", "task_id"],
            ["audit.audit_task.city_id", "audit.audit_task.id"],
            name="fk_analysis_run_city_task",
        ),
        {"schema": "audit", "comment": "Agent分析运行"},
    )

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    city_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("audit.city.id"), nullable=False, index=True
    )
    task_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    run_no: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(24), nullable=False, index=True)
    progress: Mapped[int] = mapped_column(SmallInteger, nullable=False, server_default="0")
    current_node: Mapped[str] = mapped_column(String(64), nullable=False)
    workflow_version: Mapped[str] = mapped_column(String(32), nullable=False)
    material_refs: Mapped[list[str]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    state_snapshot: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    # 仅在waiting_input期间保存中断ID和安全展示数据，不保存图片二进制或模型密钥。
    pending_interrupt: Mapped[dict[str, Any] | None] = mapped_column(
        JSONB,
        comment="当前等待人工输入的LangGraph中断信息；恢复或完成后清空",
    )
    result: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    error_code: Mapped[str | None] = mapped_column(String(64))
    error_message: Mapped[str | None] = mapped_column(Text)
    created_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class AnalysisEventModel(Base):
    """按序追加的节点进度事件，供前端展示和问题排查。"""

    __tablename__ = "analysis_event"
    __table_args__ = (
        UniqueConstraint("run_id", "sequence_no", name="uq_analysis_event_run_sequence"),
        ForeignKeyConstraint(
            ["city_id", "run_id"],
            ["audit.analysis_run.city_id", "audit.analysis_run.id"],
            name="fk_analysis_event_city_run",
        ),
        {"schema": "audit", "comment": "Agent节点事件"},
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=True), primary_key=True)
    city_id: Mapped[int] = mapped_column(SmallInteger, nullable=False, index=True)
    run_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    sequence_no: Mapped[int] = mapped_column(Integer, nullable=False)
    node_name: Mapped[str] = mapped_column(String(64), nullable=False)
    event_type: Mapped[str] = mapped_column(String(32), nullable=False)
    message: Mapped[str] = mapped_column(String(500), nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
