from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class CurrentUser:
    """首期统一权限用户；后续仅替换身份来源，不影响业务接口。"""

    id: str
    display_name: str


# 与首个数据库迁移中的开发用户保持一致，后续统一登录只替换身份解析。
DEVELOPMENT_USER = CurrentUser(
    id="00000000-0000-0000-0000-000000000001",
    display_name="开发用户",
)
