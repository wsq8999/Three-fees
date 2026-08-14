from __future__ import annotations

"""人工纠错记忆数据访问层。"""

from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.modules.correction_memories.model import CorrectionMemoryModel
from app.modules.sites.model import SiteModel


def _parse_uuid(value: str | UUID) -> UUID | None:
    """在进入PostgreSQL UUID比较前安全解析外部字符串。"""
    if isinstance(value, UUID):
        return value
    try:
        return UUID(value)
    except ValueError:
        return None


class CorrectionMemoryRepository:
    """所有纠错查询强制包含城市ID，confirmed检索还必须包含报账点ID。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, memory: CorrectionMemoryModel) -> CorrectionMemoryModel:
        """加入当前业务事务，并尽早暴露外键或唯一键冲突。"""
        self.session.add(memory)
        self.session.flush()
        return memory

    def get_by_run(self, run_id: str | UUID, city_id: int) -> CorrectionMemoryModel | None:
        """查找一次分析唯一的纠错资源，用于避免同一误判形成多条冲突记忆。"""
        normalized_id = _parse_uuid(run_id)
        if normalized_id is None:
            return None
        return self.session.scalar(
            select(CorrectionMemoryModel).where(
                CorrectionMemoryModel.analysis_run_id == normalized_id,
                CorrectionMemoryModel.city_id == city_id,
            )
        )

    def get(
        self, memory_id: str | UUID, city_id: int
    ) -> tuple[CorrectionMemoryModel, SiteModel] | None:
        """读取当前城市纠错及报账点名称，跨城市ID统一返回不存在。"""
        normalized_id = _parse_uuid(memory_id)
        if normalized_id is None:
            return None
        statement = (
            select(CorrectionMemoryModel, SiteModel)
            .join(
                SiteModel,
                (SiteModel.id == CorrectionMemoryModel.site_id)
                & (SiteModel.city_id == CorrectionMemoryModel.city_id),
            )
            .where(
                CorrectionMemoryModel.id == normalized_id,
                CorrectionMemoryModel.city_id == city_id,
            )
        )
        return self.session.execute(statement).one_or_none()

    def list_by_city(
        self,
        city_id: int,
        *,
        site_id: UUID | None = None,
        confirmed_only: bool = False,
    ) -> list[tuple[CorrectionMemoryModel, SiteModel]]:
        """查询城市内纠错；Agent调用时只返回指定报账点的confirmed记录。"""
        conditions = [CorrectionMemoryModel.city_id == city_id]
        if site_id is not None:
            conditions.append(CorrectionMemoryModel.site_id == site_id)
        if confirmed_only:
            conditions.append(CorrectionMemoryModel.status == "confirmed")
            # confirmed是流程状态；active才是当前仍被允许参与模型判断的使用状态。
            conditions.append(CorrectionMemoryModel.memory_status == "active")
        statement = (
            select(CorrectionMemoryModel, SiteModel)
            .join(
                SiteModel,
                (SiteModel.id == CorrectionMemoryModel.site_id)
                & (SiteModel.city_id == CorrectionMemoryModel.city_id),
            )
            .where(*conditions)
            .order_by(
                CorrectionMemoryModel.confirmed_at.desc().nullslast(),
                CorrectionMemoryModel.created_at.desc(),
            )
        )
        return list(self.session.execute(statement).all())
