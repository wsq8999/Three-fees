from __future__ import annotations

"""稽核任务业务服务，负责创建和按城市读取任务。"""

from uuid import UUID, uuid4

from sqlalchemy.orm import Session

from app.core.exceptions import ResourceNotFoundError
from app.core.identity import CurrentUser
from app.modules.audit_tasks.model import AuditTaskModel
from app.modules.audit_tasks.repository import AuditTaskRepository
from app.modules.audit_tasks.schemas import AuditTaskCreate, AuditTaskView
from app.modules.cities.schemas import CityContext
from app.modules.sites.model import SiteModel


def _to_view(task: AuditTaskModel, site: SiteModel, city_code: str) -> AuditTaskView:
    """隔离数据库模型与公共REST响应，并带回报账点编码供页面核对。"""
    return AuditTaskView(
        id=str(task.id),
        city_code=city_code,
        site_id=str(site.id),
        site_code=site.site_code,
        site_name=site.site_name,
        title=task.title,
        question=task.question,
        status=task.status,
        created_by=str(task.created_by),
        created_at=task.created_at,
    )


class AuditTaskService:
    """编排稽核任务事务，城市边界由可信上下文提供。"""

    def __init__(self, session: Session) -> None:
        self.session = session
        self.repository = AuditTaskRepository(session)

    def create(
        self, city: CityContext, user: CurrentUser, payload: AuditTaskCreate
    ) -> AuditTaskView:
        """创建任务；报账点必须来自当前城市的数据库主数据。

        这里不再提供“按名称查不到就创建”的便利逻辑，因为名称输入错误会形成永久脏
        数据，还可能让同一真实站点产生多份割裂的历史记忆。站点新增应走独立主数据流程。
        """
        site = self.repository.get_site(city.id, payload.site_id)
        if site is None:
            raise ResourceNotFoundError("当前城市下不存在该报账点")
        task_id = uuid4()
        task = AuditTaskModel(
            id=task_id,
            city_id=city.id,
            site_id=site.id,
            task_no=f"TASK-{task_id.hex[:12].upper()}",
            audit_type="electricity_over_limit",
            title=payload.title,
            question=payload.question,
            status="draft",
            created_by=UUID(user.id),
        )
        self.repository.add(task)
        self.session.commit()
        self.session.refresh(task)
        return _to_view(task, site, city.code)

    def get(self, task_id: str, city: CityContext) -> AuditTaskView:
        """读取当前城市任务；跨城市访问统一表现为资源不存在。"""
        row = self.repository.get(task_id, city.id)
        if row is None:
            raise ResourceNotFoundError("稽核任务不存在")
        return _to_view(*row, city.code)

    def get_model(self, task_id: str, city: CityContext) -> AuditTaskModel:
        """为内部服务返回任务模型，同时复用城市隔离检查。"""
        row = self.repository.get(task_id, city.id)
        if row is None:
            raise ResourceNotFoundError("稽核任务不存在")
        return row[0]

    def list(self, city: CityContext) -> list[AuditTaskView]:
        """按创建时间倒序返回当前城市任务。"""
        return [_to_view(*row, city.code) for row in self.repository.list_by_city(city.id)]
