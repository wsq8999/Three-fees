from __future__ import annotations

"""人工纠错记忆REST资源。"""

from fastapi import APIRouter, Query, status

from app.api.dependencies import CityContextDep, CurrentUserDep, DbSessionDep
from app.modules.correction_memories.schemas import (
    CorrectionMemoryCreate,
    CorrectionMemoryList,
    CorrectionMemoryUpdate,
    CorrectionMemoryView,
)
from app.modules.correction_memories.service import CorrectionMemoryService

router = APIRouter(prefix="/correction-memories", tags=["correction-memories"])
run_router = APIRouter(prefix="/analysis-runs", tags=["correction-memories"])


@run_router.post(
    "/{run_id}/correction-memories",
    response_model=CorrectionMemoryView,
    status_code=status.HTTP_201_CREATED,
)
def create_correction_memory_draft(
    run_id: str,
    payload: CorrectionMemoryCreate,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
) -> CorrectionMemoryView:
    """把业务员原话解析成待确认草稿，不会立即影响后续Agent。"""
    return CorrectionMemoryService(session).create_draft(
        run_id=run_id,
        message=payload.message,
        city=city,
        user=current_user,
    )


@router.patch("/{memory_id}", response_model=CorrectionMemoryView)
def update_correction_memory(
    memory_id: str,
    payload: CorrectionMemoryUpdate,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
) -> CorrectionMemoryView:
    """由业务员明确确认或驳回AI整理后的纠错草稿。"""
    return CorrectionMemoryService(session).update(
        memory_id=memory_id,
        payload=payload,
        city=city,
        user=current_user,
    )


@router.get("", response_model=CorrectionMemoryList)
def list_correction_memories(
    city: CityContextDep,
    session: DbSessionDep,
    site_id: str | None = Query(default=None),
) -> CorrectionMemoryList:
    """查询当前城市纠错，可按报账点过滤。"""
    return CorrectionMemoryList(items=CorrectionMemoryService(session).list(city, site_id=site_id))


@router.get("/{memory_id}", response_model=CorrectionMemoryView)
def get_correction_memory(
    memory_id: str,
    city: CityContextDep,
    session: DbSessionDep,
) -> CorrectionMemoryView:
    """读取一条纠错记忆及其来源运行、原判断和确认状态。"""
    return CorrectionMemoryService(session).get(memory_id, city)
