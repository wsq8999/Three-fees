from __future__ import annotations

import os
import re
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.models import (
    AiProblem,
    CorrectionInterpretationRequest,
    CorrectionInterpretationResponse,
    DocumentParseRequest,
    DocumentParseResponse,
    FactExtractionRequest,
    FactExtractionResponse,
    FieldError,
    ReasonJudgmentRequest,
    ReasonJudgmentResponse,
    ReportCompositionRequest,
    ReportCompositionResponse,
    ReportAssistanceRequest,
    ReportAssistanceResponse,
)
from app.provider import FakeModelProvider, KimiModelProvider, ModelProviderError
from app.security import ServiceAuthenticator, ServiceUnauthorizedError

TRACE_PATTERN = re.compile(r"^[A-Za-z0-9._-]{8,64}$")


def create_app(service_token: str | None = None, model_provider=None) -> FastAPI:  # type: ignore[no-untyped-def]
    configured_token = service_token if service_token is not None else os.getenv("AI_SERVICE_TOKEN")
    authenticator = ServiceAuthenticator(configured_token)
    provider = model_provider if model_provider is not None else _configured_provider()

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        authenticator.validate_configuration()
        yield

    application = FastAPI(
        title="Three Fees Internal AI Service",
        version="1.0.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )

    @application.middleware("http")
    async def assign_trace_id(request: Request, call_next):  # type: ignore[no-untyped-def]
        incoming_trace_id = request.headers.get("X-Trace-Id", "")
        trace_id = (
            incoming_trace_id if TRACE_PATTERN.fullmatch(incoming_trace_id) else uuid.uuid4().hex
        )
        request.state.trace_id = trace_id
        response = await call_next(request)
        response.headers["X-Trace-Id"] = trace_id
        return response

    @application.exception_handler(ServiceUnauthorizedError)
    async def unauthorized(request: Request, _: ServiceUnauthorizedError) -> JSONResponse:
        problem = AiProblem(
            code="AI_UNAUTHORIZED",
            message="Internal service authentication failed",
            trace_id=request.state.trace_id,
        )
        return JSONResponse(status_code=401, content=problem.model_dump(by_alias=True))

    @application.exception_handler(RequestValidationError)
    async def validation_failed(request: Request, error: RequestValidationError) -> JSONResponse:
        field_errors = [
            FieldError(
                field=".".join(str(location) for location in item["loc"] if location != "body"),
                code=item["type"],
                message="字段值不正确",
            )
            for item in error.errors()
        ]
        problem = AiProblem(
            code="AI_VALIDATION_FAILED",
            message="Request validation failed",
            trace_id=request.state.trace_id,
            field_errors=field_errors,
        )
        return JSONResponse(status_code=422, content=problem.model_dump(by_alias=True))

    @application.exception_handler(ModelProviderError)
    async def provider_failed(request: Request, error: ModelProviderError) -> JSONResponse:
        problem = AiProblem(
            code=error.code,
            message="AI provider request failed",
            trace_id=request.state.trace_id,
        )
        status = 429 if error.code == "AI_RATE_LIMITED" else 502
        return JSONResponse(status_code=status, content=problem.model_dump(by_alias=True))

    @application.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP", "modelProvider": provider.name}

    auth_dependency = Depends(authenticator.require)

    @application.post(
        "/internal/v1/document-parses",
        response_model=DocumentParseResponse,
        dependencies=[auth_dependency],
    )
    def parse_document(request: DocumentParseRequest) -> DocumentParseResponse:
        return provider.parse_document(request)

    @application.post(
        "/internal/v1/fact-extractions",
        response_model=FactExtractionResponse,
        dependencies=[auth_dependency],
    )
    def extract_facts(request: FactExtractionRequest) -> FactExtractionResponse:
        return provider.extract_facts(request)

    @application.post(
        "/internal/v1/reason-judgments",
        response_model=ReasonJudgmentResponse,
        dependencies=[auth_dependency],
    )
    def judge_reason(request: ReasonJudgmentRequest) -> ReasonJudgmentResponse:
        return provider.judge_reason(request)

    @application.post(
        "/internal/v1/report-compositions",
        response_model=ReportCompositionResponse,
        dependencies=[auth_dependency],
    )
    def compose_report(request: ReportCompositionRequest) -> ReportCompositionResponse:
        return provider.compose_report(request)

    @application.post(
        "/internal/v1/correction-interpretations",
        response_model=CorrectionInterpretationResponse,
        dependencies=[auth_dependency],
    )
    def interpret_correction(
        request: CorrectionInterpretationRequest,
    ) -> CorrectionInterpretationResponse:
        return provider.interpret_correction(request)

    @application.post(
        "/internal/v1/report-assistances",
        response_model=ReportAssistanceResponse,
        dependencies=[auth_dependency],
    )
    def assist_report(request: ReportAssistanceRequest) -> ReportAssistanceResponse:
        return provider.assist_report(request)

    return application


def _configured_provider():  # type: ignore[no-untyped-def]
    provider_name = os.getenv("AI_MODEL_PROVIDER", "fake").strip().lower()
    if provider_name == "fake":
        return FakeModelProvider()
    if provider_name == "kimi":
        return KimiModelProvider(
            api_key=os.getenv("KIMI_API_KEY", ""),
            base_url=os.getenv("KIMI_BASE_URL", "https://api.moonshot.cn/v1"),
            model=os.getenv("KIMI_MODEL", ""),
            timeout_seconds=float(os.getenv("KIMI_TIMEOUT_SECONDS", "30")),
        )
    raise RuntimeError("AI_MODEL_PROVIDER must be fake or kimi")


app = create_app()
