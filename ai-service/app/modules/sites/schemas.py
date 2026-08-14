from __future__ import annotations

"""报账点REST契约。"""

from pydantic import BaseModel, Field, field_validator


class SiteCreate(BaseModel):
    """创建报账点时由页面提交的主数据字段。

    城市不允许出现在请求体中，而是由经过后端校验的 ``X-City-Code`` 上下文决定，
    这样即使前端数据过期，也不能把苏州报账点写入南京。编码为空时由后端生成稳定
    的内部编码；对接资管系统后可以直接提交真实业务编码。
    """

    site_name: str = Field(
        min_length=2,
        max_length=160,
        description="报账点正式名称；用于页面展示和历史材料人工关联。",
    )
    site_code: str | None = Field(
        default=None,
        min_length=1,
        max_length=64,
        description="稳定业务编码；为空表示由系统生成内部编码。",
    )
    address: str | None = Field(
        default=None,
        max_length=300,
        description="报账点地址；未知时为空，不参与城市隔离和记忆检索。",
    )

    @field_validator("site_name", "site_code", "address", mode="before")
    @classmethod
    def strip_optional_text(cls, value: object) -> object:
        """清理表单首尾空白，并把可选字段的空字符串统一为 ``None``。"""
        if not isinstance(value, str):
            return value
        normalized = value.strip()
        return normalized or None


class SiteView(BaseModel):
    """前端下拉框和列表所需的报账点字段。"""

    id: str
    city_code: str
    site_code: str
    site_name: str
    address: str | None
    status: str


class SiteList(BaseModel):
    """报账点分页结果。"""

    items: list[SiteView]
    total: int
    page: int
    page_size: int
