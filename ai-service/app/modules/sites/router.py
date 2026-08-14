from __future__ import annotations

"""报账点REST路由。"""

from typing import Annotated

from fastapi import APIRouter, Query, Response, status

from app.api.dependencies import CityContextDep, CurrentUserDep, DbSessionDep
from app.modules.sites.schemas import SiteCreate, SiteList, SiteView
from app.modules.sites.service import SiteService

router = APIRouter(prefix="/sites", tags=["sites"])


@router.post(
    "",
    response_model=SiteView,
    status_code=status.HTTP_201_CREATED,
    summary="新增当前城市报账点",
)
def create_site(
    payload: SiteCreate,
    response: Response,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
) -> SiteView:
    """新增独立报账点主数据；不会同时生成材料、案例或纠错记忆。"""
    created = SiteService(session).create(city=city, user=current_user, payload=payload)
    response.headers["Location"] = f"/api/v1/sites/{created.id}"
    return created


@router.get("", response_model=SiteList, summary="查询当前城市报账点")
def list_sites(
    city: CityContextDep,
    session: DbSessionDep,
    keyword: Annotated[str | None, Query(min_length=1, max_length=160)] = None,
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 20,
) -> SiteList:
    """支持前端远程搜索，避免一次加载全部报账点。"""
    return SiteService(session).list(
        city=city,
        keyword=keyword,
        page=page,
        page_size=page_size,
    )
