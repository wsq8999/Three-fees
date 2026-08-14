from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field


class AuditTaskCreate(BaseModel):
    """创建稽核任务时由前端提交的稳定业务字段。"""

    # 任务只能绑定数据库中已经存在的报账点，避免同名脏数据和城市串用；服务层还会
    # 校验该UUID对应的站点属于请求头指定城市，字符串长度校验本身不是安全边界。
    site_id: str = Field(min_length=36, max_length=36)
    title: str = Field(min_length=2, max_length=200)
    question: str = Field(
        default="请分析本次电费超标情况，并与本市历史原因进行比较。",
        min_length=2,
        max_length=2000,
    )


class AuditTaskView(BaseModel):
    id: str
    city_code: str
    site_id: str
    site_code: str
    site_name: str
    title: str
    question: str
    status: str
    created_by: str
    created_at: datetime


class AuditTaskList(BaseModel):
    items: list[AuditTaskView]
    next_cursor: str | None = None
