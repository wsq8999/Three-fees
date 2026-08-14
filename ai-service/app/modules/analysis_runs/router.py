from __future__ import annotations

from fastapi import APIRouter, BackgroundTasks, Response, status

from app.api.dependencies import CityContextDep, CurrentUserDep, DbSessionDep
from app.modules.analysis_runs.schemas import (
    AnalysisRunCreate,
    AnalysisRunList,
    AnalysisRunResume,
    AnalysisRunView,
)
from app.modules.analysis_runs.service import AnalysisRunService
from app.modules.documents.router import DocumentStorageDep

router = APIRouter(tags=["analysis-runs"])


@router.post(
    "/audit-tasks/{task_id}/analysis-runs",
    response_model=AnalysisRunView,
    status_code=status.HTTP_202_ACCEPTED,
)
def create_analysis_run(
    task_id: str,
    payload: AnalysisRunCreate,
    background_tasks: BackgroundTasks,
    response: Response,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> AnalysisRunView:
    run = AnalysisRunService(session).create(
        task_id=task_id,
        city=city,
        user=current_user,
        payload=payload,
    )
    response.headers["Location"] = f"/api/v1/analysis-runs/{run.id}"
    background_tasks.add_task(AnalysisRunService.execute, run.id, city.id, city.code, storage)
    return run


@router.get("/analysis-runs", response_model=AnalysisRunList)
def list_analysis_runs(city: CityContextDep, session: DbSessionDep) -> AnalysisRunList:
    return AnalysisRunList(items=AnalysisRunService(session).list(city))


@router.get("/analysis-runs/{run_id}", response_model=AnalysisRunView)
def get_analysis_run(run_id: str, city: CityContextDep, session: DbSessionDep) -> AnalysisRunView:
    return AnalysisRunService(session).get(run_id, city)


@router.post(
    "/analysis-runs/{run_id}/resume",
    response_model=AnalysisRunView,
    status_code=status.HTTP_202_ACCEPTED,
)
def resume_analysis_run(
    run_id: str,
    payload: AnalysisRunResume,
    background_tasks: BackgroundTasks,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> AnalysisRunView:
    """确认系统是否超标，并从同一PostgreSQL检查点继续原运行。"""
    run = AnalysisRunService(session).queue_resume(
        run_id=run_id,
        city=city,
        user=current_user,
        payload=payload,
    )
    background_tasks.add_task(
        AnalysisRunService.resume_execute,
        run_id,
        city.id,
        city.code,
        storage,
        payload.model_dump(mode="json"),
    )
    return run
