from __future__ import annotations

"""报告编辑、审核和正式Word下载路由。"""

from fastapi import APIRouter, Response, status
from fastapi.responses import FileResponse

from app.api.dependencies import CityContextDep, CurrentUserDep, DbSessionDep
from app.modules.documents.router import DocumentStorageDep
from app.modules.reports.schemas import ReportReview, ReportUpdate, ReportView
from app.modules.reports.service import ReportService

router = APIRouter(tags=["reports"])


@router.post(
    "/analysis-runs/{run_id}/reports",
    response_model=ReportView,
    status_code=status.HTTP_201_CREATED,
)
def create_report(
    run_id: str,
    response: Response,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> ReportView:
    """把完成的AI草稿保存为可编辑报告；重复调用返回同一资源。"""
    report = ReportService(session, storage).create_from_run(run_id, city, current_user)
    response.headers["Location"] = f"/api/v1/reports/{report.id}"
    return report


@router.get("/reports/{report_id}", response_model=ReportView)
def get_report(
    report_id: str,
    city: CityContextDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> ReportView:
    """读取报告当前版本、状态和版本历史。"""
    return ReportService(session, storage).get(report_id, city)


@router.patch("/reports/{report_id}", response_model=ReportView)
def update_report(
    report_id: str,
    payload: ReportUpdate,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> ReportView:
    """保存一个新的完整报告版本。"""
    return ReportService(session, storage).update(report_id, city, current_user, payload)


@router.post("/reports/{report_id}/reviews", response_model=ReportView)
def review_report(
    report_id: str,
    payload: ReportReview,
    city: CityContextDep,
    current_user: CurrentUserDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> ReportView:
    """提交、退回或审核通过一份报告。"""
    return ReportService(session, storage).review(report_id, city, current_user, payload)


@router.get("/reports/{report_id}/content", response_class=FileResponse)
def download_report(
    report_id: str,
    city: CityContextDep,
    session: DbSessionDep,
    storage: DocumentStorageDep,
) -> FileResponse:
    """仅下载当前城市已经审核通过的正式Word。"""
    storage_key, filename = ReportService(session, storage).approved_file(report_id, city)
    return FileResponse(
        storage.resolve(storage_key),
        media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        filename=filename,
    )
