from __future__ import annotations

"""江苏13市基础数据服务。"""

from sqlalchemy.orm import Session

from app.modules.cities.repository import CityRepository
from app.modules.cities.schemas import CityContext, CityView


class CityService:
    """从数据库读取固定城市主数据并转换为公共契约。"""

    def __init__(self, session: Session) -> None:
        self.repository = CityRepository(session)

    def list_all(self) -> list[CityView]:
        """返回所有可使用的城市助手。"""
        return [
            CityView(id=item.id, code=item.code, name=item.name)
            for item in self.repository.list_active()
        ]

    def get_context(self, code: str) -> CityContext | None:
        """为业务请求生成包含数据库ID的可信城市上下文。"""
        city = self.repository.get_active_by_code(code)
        if city is None:
            return None
        return CityContext(id=city.id, code=city.code, name=city.name)
