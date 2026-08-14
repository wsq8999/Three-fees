from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.modules.report_image_analysis.router import router as report_image_analysis_router


def create_app() -> FastAPI:
    application = FastAPI(
        title="Three-fees AI Image Analysis",
        version="0.1.0",
        description="江苏 AI Agent 图片分析适配服务",
        openapi_url="/api/v1/openapi.json",
        docs_url="/docs",
    )
    application.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    application.include_router(report_image_analysis_router, prefix="/api/v1")
    return application


app = create_app()
