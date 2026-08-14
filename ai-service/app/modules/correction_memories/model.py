from __future__ import annotations

"""业务员纠错记忆数据库模型。"""

from datetime import datetime
from decimal import Decimal
from typing import Any
from uuid import UUID

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Integer,
    Numeric,
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


class CorrectionMemoryModel(Base):
    """一次AI误判经过业务员确认后形成的长期记忆。

    本表同时保存原判断、业务员原话和AI结构化理解，避免只保留最终一句原因而失去
    审计上下文。只有 ``confirmed`` 状态会被Agent读取；draft和rejected永远不能影响
    后续任务，从数据层面落实“先让人确认，再让AI学习”。
    """

    __tablename__ = "correction_memory"
    __table_args__ = (
        # 供未来其他城市隔离表安全引用，并让数据库能够校验复合外键范围。
        UniqueConstraint("city_id", "id", name="uq_correction_memory_city_id"),
        # 首期一次分析只允许一条纠错，重复修改更新同一资源，避免多条相互冲突的记忆。
        UniqueConstraint("analysis_run_id", name="uq_correction_memory_analysis_run"),
        ForeignKeyConstraint(
            ["city_id", "site_id"],
            ["audit.site.city_id", "audit.site.id"],
            name="fk_correction_memory_city_site",
        ),
        ForeignKeyConstraint(
            ["city_id", "task_id"],
            ["audit.audit_task.city_id", "audit.audit_task.id"],
            name="fk_correction_memory_city_task",
        ),
        ForeignKeyConstraint(
            ["city_id", "analysis_run_id"],
            ["audit.analysis_run.city_id", "audit.analysis_run.id"],
            name="fk_correction_memory_city_run",
        ),
        CheckConstraint(
            "status IN ('draft', 'confirmed', 'rejected', 'archived')",
            name="status_values",
        ),
        # draft/confirmed描述纠错流程；memory_status单独决定该记忆能否进入RAG。
        CheckConstraint(
            "memory_status IN ('active', 'paused', 'invalidated')",
            name="memory_status_values",
        ),
        CheckConstraint(
            "confidence >= 0 AND confidence <= 1",
            name="confidence_range",
        ),
        {"schema": "audit", "comment": "业务员确认的AI纠错记忆"},
    )

    # 记忆稳定ID；草稿确认或修改时不会改变，便于审计日志持续引用。
    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    # 城市与报账点共同定义记忆作用域；苏州记忆在南京查询中不可见。
    city_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("audit.city.id"), nullable=False, index=True
    )
    site_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    # 保存来源任务和具体运行，确保能还原“AI当时看到了什么、判断了什么”。
    task_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    analysis_run_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    # draft等待人工确认；confirmed可参与RAG；rejected和archived均不参与。
    status: Mapped[str] = mapped_column(
        String(24), nullable=False, server_default="draft", index=True
    )
    # 新纠错默认暂停，只有人工确认后才切为active，防止草稿被模型提前学习。
    memory_status: Mapped[str] = mapped_column(
        String(24), nullable=False, server_default="paused", index=True
    )
    # 原判断按提交纠错时的结果快照保存，后续运行结果变化也不会破坏审计依据。
    original_reason: Mapped[str | None] = mapped_column(Text)
    original_reason_category: Mapped[str | None] = mapped_column(String(100))
    original_judgment: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, server_default=text("'{}'::jsonb")
    )
    # user_message必须原样保留；corrected_reason是Kimi解析并经业务员确认后的标准表达。
    user_message: Mapped[str] = mapped_column(Text, nullable=False)
    corrected_reason: Mapped[str] = mapped_column(Text, nullable=False)
    reason_category: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    # 适用条件用于下次判断“这条纠错是否真的适合当前情况”，而不是无条件覆盖事实。
    applicability_conditions: Mapped[list[object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    # 关联本次Word元素；即使业务员只说大白话，AI也可把纠错与现有事实证据对齐。
    supporting_element_ids: Mapped[list[object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    # 解释摘要和不确定项显示给业务员确认，不能把模型理解过程隐藏后直接学习。
    interpretation_summary: Mapped[str] = mapped_column(Text, nullable=False)
    uncertain_items: Mapped[list[object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    confidence: Mapped[Decimal] = mapped_column(Numeric(4, 3), nullable=False)
    # 模型和提示词版本用于批量评估旧记忆是否需要重新解释。
    model_name: Mapped[str] = mapped_column(String(100), nullable=False)
    prompt_version: Mapped[str] = mapped_column(String(64), nullable=False)
    # 创建人和确认人分开记录；当前首期可能是同一开发用户，字段仍具有审计价值。
    created_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    confirmed_by: Mapped[UUID | None] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id")
    )
    # 每次人工修改、确认或驳回都会递增，便于未来做并发控制和历史版本扩展。
    version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    confirmed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
