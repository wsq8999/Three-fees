from __future__ import annotations

"""FastAPI应用入口，集中装配中间件、异常处理和版本化路由。"""

from contextlib import asynccontextmanager
from os import getenv

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.agents.audit.persistence import initialize_checkpoint_storage
from app.api.errors import register_exception_handlers
from app.api.middleware import TraceIdMiddleware
from app.api.v1.router import api_router
from app.core.config import get_settings
from app.db import registry as _model_registry  # noqa: F401


@asynccontextmanager
async def lifespan(application: FastAPI):
    """在接收请求前初始化持久化检查点，避免分析请求内执行数据库DDL。"""
    if application.state.initialize_checkpoints:
        initialize_checkpoint_storage()
    yield


def create_app(*, initialize_checkpoints: bool | None = None) -> FastAPI:
    """创建独立应用实例；接口测试可关闭外部检查点初始化并注入内存Saver。"""
    settings = get_settings()
    application = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        description="江苏 13 市 AI 稽核助手 REST API",
        openapi_url="/api/v1/openapi.json",
        docs_url="/docs",
        redoc_url="/redoc",
        lifespan=lifespan,
    )
    if initialize_checkpoints is None:
        initialize_checkpoints = getenv("AI_INITIALIZE_CHECKPOINTS", "true").lower() == "true"
    application.state.initialize_checkpoints = initialize_checkpoints
    application.add_middleware(
        CORSMiddleware,
        allow_origins=[settings.frontend_origin],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    application.add_middleware(TraceIdMiddleware)
    register_exception_handlers(application)
    application.include_router(api_router, prefix="/api/v1")
    return application


app = create_app()
