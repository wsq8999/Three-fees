from __future__ import annotations

"""城市数据访问层。"""

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.modules.cities.model import CityModel


class CityRepository:
    """所有城市查询都限定有效状态。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def list_active(self) -> list[CityModel]:
        """按固定ID顺序返回13市。"""
        statement = select(CityModel).where(CityModel.status == "active").order_by(CityModel.id)
        return list(self.session.scalars(statement))

    def get_active_by_code(self, code: str) -> CityModel | None:
        """按稳定英文代码查找城市。"""
        statement = select(CityModel).where(
            CityModel.code == code,
            CityModel.status == "active",
        )
        return self.session.scalar(statement)
