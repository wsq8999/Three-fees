from __future__ import annotations

"""统一记忆管理REST资源。"""

from typing import Annotated, Literal

from fastapi import APIRouter, Query, status

from app.api.dependencies import CityContextDep, CurrentUserDep, DbSessionDep
from app.modules.memory_governance.schemas import (
    MemoryFlagCreate,
    MemoryFlagResolve,
    MemoryFlagView,
    MemoryList,
    MemoryRevisionCreate,
    MemoryRevisionView,
    MemoryStatusUpdate,
    MemoryType,
    MemoryView,
)
from app.modules.memory_governance.service import MemoryGovernanceService

router = APIRouter(tags=["memories"])


@router.get("/memories", response_model=MemoryList)
def list_memories(
    city: CityContextDep,
    session: DbSessionDep,
    memory_type: MemoryType | None = None,
    memory_status: Literal["active", "paused", "invalidated"] | None = None,
    site_id: str | None = None,
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=100)] = 50,
) -> MemoryList:
    """查询当前城市全部长期记忆，供独立记忆管理页面使用。"""
    items, total = MemoryGovernanceService(session).list(
        city,
        memory_type=memory_type,
        memory_status=memory_status,
        site_id=site_id,
        page=page,
        page_size=page_size,
    )
    return MemoryList(items=items, total=total, page=page, page_size=page_size)


@router.patch("/memories/{memory_type}/{memory_id}/status", response_model=MemoryView)
def update_memory_status(
    memory_type: MemoryType,
    memory_id: str,
    payload: MemoryStatusUpdate,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
) -> MemoryView:
    """暂停或恢复一条未确认错误的记忆。"""
    return MemoryGovernanceService(session).update_status(
        memory_type, memory_id, payload, city, current_user
    )


@router.post(
    "/memories/{memory_type}/{memory_id}/flags",
    response_model=MemoryFlagView,
    status_code=status.HTTP_201_CREATED,
)
def flag_memory(
    memory_type: MemoryType,
    memory_id: str,
    payload: MemoryFlagCreate,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
) -> MemoryFlagView:
    """独立标错任意历史案例或人工纠错，并立即暂停其RAG使用。"""
    return MemoryGovernanceService(session).create_flag(
        memory_type, memory_id, payload, city, current_user
    )


@router.patch("/memory-flags/{flag_id}", response_model=MemoryFlagView)
def resolve_memory_flag(
    flag_id: str,
    payload: MemoryFlagResolve,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
) -> MemoryFlagView:
    """将标错判为误标并恢复，或确认错误并使旧版本失效。"""
    return MemoryGovernanceService(session).resolve_flag(flag_id, payload, city, current_user)


@router.post(
    "/memories/{memory_type}/{memory_id}/revisions",
    response_model=MemoryRevisionView,
    status_code=status.HTTP_201_CREATED,
)
def revise_memory(
    memory_type: MemoryType,
    memory_id: str,
    payload: MemoryRevisionCreate,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
) -> MemoryRevisionView:
    """修改已确认错误的记忆，保留旧快照并生成重新启用的新版本。"""
    return MemoryGovernanceService(session).revise(
        memory_type, memory_id, payload, city, current_user
    )
