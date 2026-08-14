from __future__ import annotations

"""将业务异常和参数异常转换为统一的问题响应。"""

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.core.exceptions import AppError

logger = logging.getLogger(__name__)


def _problem(
    request: Request,
    *,
    status: int,
    code: str,
    title: str,
    detail: str,
    errors: list[dict[str, Any]] | None = None,
) -> JSONResponse:
    """构造包含业务错误码和链路ID的标准问题响应。"""
    body: dict[str, Any] = {
        "type": f"urn:jiangsu-audit-agent:error:{code}",
        "title": title,
        "status": status,
        "detail": detail,
        "instance": request.url.path,
        "code": code,
        "trace_id": getattr(request.state, "trace_id", ""),
    }
    if errors:
        body["errors"] = errors
    return JSONResponse(body, status_code=status, media_type="application/problem+json")


def register_exception_handlers(app: FastAPI) -> None:
    """注册全局异常处理器，保证前端只需处理一种错误结构。"""

    @app.exception_handler(AppError)
    async def handle_app_error(request: Request, exc: AppError) -> JSONResponse:
        return _problem(
            request,
            status=exc.status,
            code=exc.code,
            title=exc.title,
            detail=exc.detail,
        )

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return _problem(
            request,
            status=422,
            code="validation_error",
            title="请求参数校验失败",
            detail="一个或多个请求参数不符合接口约定",
            errors=exc.errors(),
        )

    @app.exception_handler(Exception)
    async def handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
        """记录完整服务端异常，但只向前端返回不泄漏内部细节的统一JSON。"""
        logger.exception(
            "Unhandled API error trace_id=%s path=%s",
            getattr(request.state, "trace_id", ""),
            request.url.path,
            exc_info=exc,
        )
        return _problem(
            request,
            status=500,
            code="internal_server_error",
            title="服务端处理失败",
            detail="服务端处理请求时发生异常，请根据页面提示的链路ID排查",
        )
