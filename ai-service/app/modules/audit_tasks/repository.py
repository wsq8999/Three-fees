from __future__ import annotations

"""稽核任务和报账点的数据访问层。"""

from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.modules.audit_tasks.model import AuditTaskModel
from app.modules.sites.model import SiteModel


class AuditTaskRepository:
    """所有任务查询都显式使用城市ID，防止跨城市读取。"""

    def __init__(self, session: Session) -> None:
        self.session = session

    def get_site(self, city_id: int, site_id: str | UUID) -> SiteModel | None:
        """只读取当前城市的有效报账点，UUID无效时按未找到处理。

        同时限制 ``id``、``city_id`` 和 ``active`` 状态，保证已停用站点或其他城市站点
        都不能被新任务引用；统一返回None可避免数据库UUID转换异常泄漏成500。
        """
        try:
            normalized_id = site_id if isinstance(site_id, UUID) else UUID(site_id)
        except ValueError:
            return None
        statement = select(SiteModel).where(
            SiteModel.id == normalized_id,
            SiteModel.city_id == city_id,
            SiteModel.status == "active",
        )
        return self.session.scalar(statement)

    def add(self, task: AuditTaskModel) -> AuditTaskModel:
        """新增任务并在当前事务中取得数据库默认字段。"""
        self.session.add(task)
        self.session.flush()
        return task

    def get(self, task_id: str | UUID, city_id: int) -> tuple[AuditTaskModel, SiteModel] | None:
        """读取当前城市任务及其报账点。"""
        try:
            normalized_id = task_id if isinstance(task_id, UUID) else UUID(task_id)
        except ValueError:
            # 外部路径参数格式错误时按“资源不存在”处理，避免UUID转换错误泄漏为500。
            return None
        statement = (
            select(AuditTaskModel, SiteModel)
            .join(
                SiteModel,
                (SiteModel.id == AuditTaskModel.site_id)
                & (SiteModel.city_id == AuditTaskModel.city_id),
            )
            .where(AuditTaskModel.id == normalized_id, AuditTaskModel.city_id == city_id)
        )
        return self.session.execute(statement).one_or_none()

    def list_by_city(self, city_id: int) -> list[tuple[AuditTaskModel, SiteModel]]:
        """按创建时间倒序列出当前城市任务。"""
        statement = (
            select(AuditTaskModel, SiteModel)
            .join(
                SiteModel,
                (SiteModel.id == AuditTaskModel.site_id)
                & (SiteModel.city_id == AuditTaskModel.city_id),
            )
            .where(AuditTaskModel.city_id == city_id)
            .order_by(AuditTaskModel.created_at.desc())
        )
        return list(self.session.execute(statement).all())
