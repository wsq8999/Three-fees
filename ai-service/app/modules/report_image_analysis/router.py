from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Header, HTTPException, status

from app.integrations.ai.base import AIProviderError
from app.modules.report_image_analysis.schemas import (
    ReportImageAnalysisRequest,
    ReportImageAnalysisResponse,
)
from app.modules.report_image_analysis.service import analyze_report_images, normalize_ai_error
from app.security import ServiceAuthenticator, ServiceUnauthorizedError

router = APIRouter(prefix="/report-image-analysis", tags=["report-image-analysis"])


def require_service_token(authorization: Annotated[str | None, Header()] = None) -> None:
    from os import getenv

    authenticator = ServiceAuthenticator(getenv("AI_SERVICE_TOKEN"))
    try:
        authenticator.validate_configuration()
        authenticator.require(authorization)
    except ServiceUnauthorizedError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "AI_SERVICE_UNAUTHORIZED", "message": "AI 服务令牌不正确"},
        ) from exc
    except RuntimeError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "AI_SERVICE_NOT_CONFIGURED", "message": str(exc)},
        ) from exc


@router.post("", response_model=ReportImageAnalysisResponse)
def create_report_image_analysis(
    payload: ReportImageAnalysisRequest,
    authorization: Annotated[str | None, Header()] = None,
) -> ReportImageAnalysisResponse:
    require_service_token(authorization)
    try:
        return analyze_report_images(payload)
    except AIProviderError as exc:
        http_status, detail = normalize_ai_error(exc)
        raise HTTPException(status_code=http_status, detail=detail) from exc
