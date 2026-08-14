from __future__ import annotations

from pydantic import BaseModel


class CityView(BaseModel):
    id: int
    code: str
    name: str


class CityList(BaseModel):
    items: list[CityView]


class CityContext(BaseModel):
    """后端校验后产生的可信城市上下文，不接受请求体直接构造。"""

    id: int
    code: str
    name: str
