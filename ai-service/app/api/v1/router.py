from __future__ import annotations

from fastapi import APIRouter
from sqlalchemy import text

from app.api.dependencies import DbSessionDep
from app.modules.analysis_runs.router import router as analysis_runs_router
from app.modules.audit_cases.router import router as audit_cases_router
from app.modules.audit_tasks.router import router as audit_tasks_router
from app.modules.cities.router import router as cities_router
from app.modules.correction_memories.router import router as correction_memories_router
from app.modules.correction_memories.router import run_router as run_corrections_router
from app.modules.documents.router import (
    document_elements_router,
    document_types_router,
    parse_runs_router,
)
from app.modules.documents.router import router as documents_router
from app.modules.memory_governance.router import router as memory_governance_router
from app.modules.reports.router import router as reports_router
from app.modules.report_image_analysis.router import router as report_image_analysis_router
from app.modules.sites.router import router as sites_router

api_router = APIRouter()


@api_router.get("/health/live", tags=["system"], summary="服务存活检查")
def health_live() -> dict[str, str]:
    """仅确认Web进程仍能响应请求。"""
    return {"status": "ok"}


@api_router.get("/health/ready", tags=["system"], summary="服务就绪检查")
def health_ready(session: DbSessionDep) -> dict[str, str]:
    """通过一次轻量查询确认应用可以访问数据库。"""
    session.execute(text("SELECT 1"))
    return {"status": "ok", "database": "ok"}


api_router.include_router(cities_router)
api_router.include_router(memory_governance_router)
api_router.include_router(correction_memories_router)
api_router.include_router(run_corrections_router)
api_router.include_router(audit_tasks_router)
api_router.include_router(audit_cases_router)
api_router.include_router(analysis_runs_router)
api_router.include_router(reports_router)
api_router.include_router(report_image_analysis_router)
api_router.include_router(documents_router)
api_router.include_router(document_types_router)
api_router.include_router(sites_router)
api_router.include_router(parse_runs_router)
api_router.include_router(document_elements_router)
