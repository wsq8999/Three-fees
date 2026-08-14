from __future__ import annotations

"""把历史报告转化为城市隔离、可检索的稽核案例。"""

from datetime import datetime
from decimal import Decimal
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


class AuditCaseModel(Base):
    """一份历史报告经过AI结构化后形成的一条长期案例记忆。

    这张表不复制原始Word内容。原文件仍由 ``source_document`` 保存，文字、表格和
    图片定位仍由 ``document_element`` 保存；本表只保存RAG检索真正需要的精简结论。
    这样既能控制模型上下文长度，也能通过 ``evidence_element_ids`` 回到原文核对。
    """

    __tablename__ = "audit_case"
    __table_args__ = (
        # 复合唯一键供其他城市隔离表建立(city_id, case_id)外键，防止跨市引用。
        UniqueConstraint("city_id", "id", name="uq_audit_case_city_id"),
        # 同一份历史报告只维护一条案例；重新分析时更新版本，不制造重复记忆。
        UniqueConstraint("source_document_id", name="uq_audit_case_source_document"),
        # 把城市同时放进外键，使“苏州案例绑定南京报账点”在数据库层也无法写入。
        ForeignKeyConstraint(
            ["city_id", "site_id"],
            ["audit.site.city_id", "audit.site.id"],
            name="fk_audit_case_city_site",
        ),
        # 历史报告与案例必须属于同一城市，不能仅依赖应用代码约束。
        ForeignKeyConstraint(
            ["city_id", "source_document_id"],
            ["audit.source_document.city_id", "audit.source_document.id"],
            name="fk_audit_case_city_document",
        ),
        # pending表示模型处理中；ready才能参与RAG；failed可重试；archived不再使用。
        CheckConstraint(
            "status IN ('pending', 'ready', 'failed', 'archived')",
            name="status_values",
        ),
        # 生成状态与使用状态分离：ready只说明解析成功，active才允许进入RAG。
        CheckConstraint(
            "memory_status IN ('active', 'paused', 'invalidated')",
            name="memory_status_values",
        ),
        # 置信度统一使用0~1，避免前端同时处理百分数和小数两套口径。
        CheckConstraint(
            "confidence IS NULL OR (confidence >= 0 AND confidence <= 1)",
            name="confidence_range",
        ),
        {"schema": "audit", "comment": "历史报告结构化案例记忆"},
    )

    # 案例自身的稳定标识；模型重新分析不会改变该ID。
    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True)
    # 13市隔离的首要查询条件，所有案例查询都必须携带该字段。
    city_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("audit.city.id"), nullable=False, index=True
    )
    # 报账点决定“这个城市助手向谁学习”，是同点历史检索的核心键。
    site_id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), nullable=False, index=True)
    # 指向不可替代的原始历史报告，供证据回看和重新分析使用。
    source_document_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), nullable=False, index=True
    )
    # 只有ready案例会进入原因判断；失败案例保留错误信息而不是静默消失。
    status: Mapped[str] = mapped_column(
        String(24), nullable=False, server_default="pending", index=True
    )
    # 用户可独立暂停或标错一条已生成案例，而不用删除原报告和审计证据。
    memory_status: Mapped[str] = mapped_column(
        String(24), nullable=False, server_default="paused", index=True
    )
    # 原报告覆盖的缴费/稽核期间；文档未明确时允许为空，禁止模型猜测。
    billing_period: Mapped[str | None] = mapped_column(String(100))
    # 一份报告可能同时涉及电费、日均电量、额定功率等多个超标项。
    over_limit_items: Mapped[list[object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    # 报告有证据支持的主要原因；证据不足时为空而不是生成似是而非的结论。
    primary_reason: Mapped[str | None] = mapped_column(Text)
    # 稳定、短小的业务分类，用于同类历史聚合和后续城市规则统计。
    reason_category: Mapped[str | None] = mapped_column(String(100), index=True)
    # 支撑原因的关键事实摘要，减少RAG时重复发送整篇历史Word的成本。
    key_facts: Mapped[list[object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    # 对应document_element.id；业务员可据此定位原文段落、表格或图片。
    evidence_element_ids: Mapped[list[object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    # 模型无法确认或报告内部冲突的内容，必须显式保留供人工复核。
    uncertain_items: Mapped[list[object]] = mapped_column(
        JSONB, nullable=False, server_default=text("'[]'::jsonb")
    )
    # Numeric而非浮点数，保证置信度落库和读取时不会产生二进制浮点误差。
    confidence: Mapped[Decimal | None] = mapped_column(Numeric(4, 3))
    # 记录实际模型和提示词版本，使历史结论可以审计、比较和批量重跑。
    model_name: Mapped[str | None] = mapped_column(String(100))
    prompt_version: Mapped[str | None] = mapped_column(String(64))
    # 稳定错误码供程序决定是否重试；安全错误文本供页面和运维排查。
    error_code: Mapped[str | None] = mapped_column(String(64))
    error_message: Mapped[str | None] = mapped_column(String(500))
    # 首期所有人权限一致，但仍记录发起者，为未来企业登录和审计保留依据。
    created_by: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("audit.app_user.id"), nullable=False
    )
    # 每次成功重新分析递增，后续纠错和模型升级可识别案例是否发生变化。
    version: Mapped[int] = mapped_column(Integer, nullable=False, server_default="1")
    # created_at表示案例首次建立；analyzed_at表示最近一次模型成功完成。
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    # updated_at预留给统一更新时间机制和后续人工修订。
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    analyzed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
