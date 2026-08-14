from __future__ import annotations

"""只追加操作审计日志仓储。"""

from typing import Any
from uuid import UUID

from sqlalchemy.orm import Session

from app.modules.audit_logs.model import AuditLogModel


class AuditLogRepository:
    """在业务事务内追加不可修改的审计记录。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def append(
        self,
        *,
        city_id: int | None,
        user_id: UUID | None,
        action: str,
        entity_type: str,
        entity_id: str,
        before_data: dict[str, Any] | None = None,
        after_data: dict[str, Any] | None = None,
    ) -> None:
        """把一次状态变化加入当前事务，随业务成功一起提交。"""
        self.session.add(
            AuditLogModel(
                city_id=city_id,
                user_id=user_id,
                action=action,
                entity_type=entity_type,
                entity_id=entity_id,
                before_data=before_data,
                after_data=after_data,
            )
        )
