from __future__ import annotations

"""REST接口公共依赖，负责生成可信的用户和城市上下文。"""

from typing import Annotated

from fastapi import Depends, Header
from sqlalchemy.orm import Session

from app.core.exceptions import AppError
from app.core.identity import DEVELOPMENT_USER, CurrentUser
from app.db.session import get_db
from app.modules.cities.schemas import CityContext
from app.modules.cities.service import CityService

# 统一声明数据库会话依赖，既便于各路由复用，也避免在函数默认值中直接调用 Depends。
DbSessionDep = Annotated[Session, Depends(get_db)]


def get_current_user() -> CurrentUser:
    """返回当前登录用户；首期统一权限，后续替换为企业登录解析。"""
    return DEVELOPMENT_USER


def get_city_code(
    session: DbSessionDep,
    x_city_code: Annotated[str | None, Header(alias="X-City-Code")] = None,
) -> CityContext:
    """校验客户端选择的城市，避免业务模块直接信任请求头原值。"""
    if not x_city_code:
        raise AppError(
            status=400,
            code="city_context_required",
            title="缺少城市上下文",
            detail="业务请求必须提供 X-City-Code 请求头",
        )
    normalized = x_city_code.strip().lower()
    city = CityService(session).get_context(normalized)
    if city is None:
        raise AppError(
            status=400,
            code="invalid_city_context",
            title="城市上下文无效",
            detail=f"不支持城市代码：{x_city_code}",
        )
    return city


CurrentUserDep = Annotated[CurrentUser, Depends(get_current_user)]
CityContextDep = Annotated[CityContext, Depends(get_city_code)]
