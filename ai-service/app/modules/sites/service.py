from __future__ import annotations

"""报账点主数据服务。"""

from uuid import UUID, uuid4

from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.exceptions import ConflictError
from app.core.identity import CurrentUser
from app.modules.audit_logs.repository import AuditLogRepository
from app.modules.cities.schemas import CityContext
from app.modules.sites.model import SiteModel
from app.modules.sites.repository import SiteRepository
from app.modules.sites.schemas import SiteCreate, SiteList, SiteView


def _to_view(site: SiteModel, city_code: str) -> SiteView:
    """把数据库模型转换成稳定的REST响应，避免前端依赖内部字段。"""
    return SiteView(
        id=str(site.id),
        city_code=city_code,
        site_code=site.site_code,
        site_name=site.site_name,
        address=site.address,
        status=site.status,
    )


class SiteService:
    """创建和查询当前城市的报账点主数据。"""

    def __init__(self, session: Session) -> None:
        self.session = session
        self.repository = SiteRepository(session)

    def list(
        self, *, city: CityContext, keyword: str | None, page: int, page_size: int
    ) -> SiteList:
        """返回用于远程搜索下拉框的分页数据。"""
        rows, total = self.repository.list_page(
            city_id=city.id,
            keyword=keyword,
            page=page,
            page_size=page_size,
        )
        return SiteList(
            items=[_to_view(row, city.code) for row in rows],
            total=total,
            page=page,
            page_size=page_size,
        )

    def create(self, *, city: CityContext, user: CurrentUser, payload: SiteCreate) -> SiteView:
        """在当前城市创建一条不携带任何历史记忆的报账点主数据。

        报账点、审计日志在同一事务中提交，避免主数据创建成功但操作来源不可追踪。
        这里只创建 ``site``，不会创建历史案例、材料关联或纠错记忆，因此适合验证
        新报账点的首次分析流程。
        """
        if self.repository.get_by_name(city_id=city.id, site_name=payload.site_name):
            raise ConflictError("当前城市已存在同名报账点，请直接搜索并选择")

        site_id = uuid4()
        # 没有真实资管编码时使用不会碰撞的内部编码；后续对接主数据时仍可传真实编码。
        site_code = payload.site_code or f"SITE-{city.code.upper()}-{site_id.hex[:12].upper()}"
        if self.repository.get_by_code(city_id=city.id, site_code=site_code):
            raise ConflictError("当前城市已存在相同报账点编码")

        site = SiteModel(
            id=site_id,
            city_id=city.id,
            site_code=site_code,
            site_name=payload.site_name,
            address=payload.address,
            status="active",
        )
        self.repository.add(site)
        AuditLogRepository(self.session).append(
            city_id=city.id,
            user_id=UUID(user.id),
            action="site.created",
            entity_type="site",
            entity_id=str(site.id),
            after_data={
                "site_code": site.site_code,
                "site_name": site.site_name,
                "address": site.address,
                "status": site.status,
            },
        )
        try:
            self.session.commit()
        except IntegrityError as error:
            # 数据库唯一约束是并发创建时的最后防线；回滚后返回稳定业务错误而非500。
            self.session.rollback()
            raise ConflictError("当前城市已存在相同报账点编码") from error
        self.session.refresh(site)
        return _to_view(site, city.code)
