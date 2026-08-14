from __future__ import annotations

"""报账点数据访问仓储。"""

from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.modules.sites.model import SiteModel


class SiteRepository:
    """所有查询和重复检查都绑定可信城市ID。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def list_page(
        self, *, city_id: int, keyword: str | None, page: int, page_size: int
    ) -> tuple[list[SiteModel], int]:
        """按名称或编码搜索有效报账点。"""
        conditions = [SiteModel.city_id == city_id, SiteModel.status == "active"]
        if keyword:
            pattern = f"%{keyword.strip()}%"
            conditions.append(
                or_(SiteModel.site_name.ilike(pattern), SiteModel.site_code.ilike(pattern))
            )
        total = self.session.scalar(select(func.count()).select_from(SiteModel).where(*conditions))
        statement = (
            select(SiteModel)
            .where(*conditions)
            .order_by(SiteModel.site_name, SiteModel.id)
            .offset((page - 1) * page_size)
            .limit(page_size)
        )
        return list(self.session.scalars(statement)), total or 0

    def get_by_name(self, *, city_id: int, site_name: str) -> SiteModel | None:
        """按忽略大小写的完整名称检查同城市重复报账点。"""
        statement = select(SiteModel).where(
            SiteModel.city_id == city_id,
            func.lower(SiteModel.site_name) == site_name.lower(),
        )
        return self.session.scalar(statement)

    def get_by_code(self, *, city_id: int, site_code: str) -> SiteModel | None:
        """按忽略大小写的完整编码检查同城市重复报账点。"""
        statement = select(SiteModel).where(
            SiteModel.city_id == city_id,
            func.lower(SiteModel.site_code) == site_code.lower(),
        )
        return self.session.scalar(statement)

    def add(self, site: SiteModel) -> None:
        """把新报账点加入当前事务；提交由服务层统一控制。"""
        self.session.add(site)
