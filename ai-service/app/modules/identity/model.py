from __future__ import annotations

"""系统登录用户数据库模型；首期不区分业务角色。"""

from datetime import datetime
from uuid import UUID

from sqlalchemy import DateTime, String, text
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class AppUserModel(Base):
    """记录操作人身份，用于任务归属和审计追踪。"""

    __tablename__ = "app_user"
    __table_args__ = {"schema": "audit", "comment": "系统登录用户"}

    id: Mapped[UUID] = mapped_column(PGUUID(as_uuid=True), primary_key=True, comment="用户ID")
    account: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, comment="账号")
    display_name: Mapped[str] = mapped_column(String(64), nullable=False, comment="展示姓名")
    status: Mapped[str] = mapped_column(String(16), nullable=False, comment="用户状态")
    identity_provider: Mapped[str] = mapped_column(String(32), nullable=False, comment="身份来源")
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )
