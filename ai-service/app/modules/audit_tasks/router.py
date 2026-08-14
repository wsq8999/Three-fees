from __future__ import annotations

from fastapi import APIRouter, status

from app.api.dependencies import CityContextDep, CurrentUserDep, DbSessionDep
from app.modules.audit_tasks.schemas import AuditTaskCreate, AuditTaskList, AuditTaskView
from app.modules.audit_tasks.service import AuditTaskService

router = APIRouter(prefix="/audit-tasks", tags=["audit-tasks"])


@router.post("", response_model=AuditTaskView, status_code=status.HTTP_201_CREATED)
def create_audit_task(
    payload: AuditTaskCreate,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
) -> AuditTaskView:
    return AuditTaskService(session).create(city, current_user, payload)


@router.get("", response_model=AuditTaskList)
def list_audit_tasks(city: CityContextDep, session: DbSessionDep) -> AuditTaskList:
    return AuditTaskList(items=AuditTaskService(session).list(city))


@router.get("/{task_id}", response_model=AuditTaskView)
def get_audit_task(task_id: str, city: CityContextDep, session: DbSessionDep) -> AuditTaskView:
    return AuditTaskService(session).get(task_id, city)
