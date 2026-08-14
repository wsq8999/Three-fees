from __future__ import annotations

from fastapi import APIRouter

from app.api.dependencies import DbSessionDep
from app.modules.cities.schemas import CityList
from app.modules.cities.service import CityService

router = APIRouter(prefix="/cities", tags=["cities"])


@router.get("", response_model=CityList, summary="获取全部城市助手")
def list_cities(session: DbSessionDep) -> CityList:
    """从数据库返回固定13市。"""
    return CityList(items=CityService(session).list_all())
