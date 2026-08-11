from __future__ import annotations

import base64
import binascii
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )


class RequestMetadata(ApiModel):
    contract_version: Literal["1.0"]
    workflow_version: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9._-]+$")
    job_id: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9._-]+$")
    idempotency_key: str = Field(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9._-]+$")
    input_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    trace_id: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9._-]+$")


class ResponseMetadata(ApiModel):
    contract_version: Literal["1.0"] = "1.0"
    job_id: str
    trace_id: str
    model_provider: Literal["fake", "kimi"] = "fake"

    @classmethod
    def from_request(cls, metadata: RequestMetadata, provider: str = "fake") -> Self:
        return cls(job_id=metadata.job_id, trace_id=metadata.trace_id, model_provider=provider)


class Segment(ApiModel):
    segment_id: str = Field(min_length=1, max_length=64)
    text: str = Field(min_length=1, max_length=30_000)


class DocumentParseRequest(ApiModel):
    metadata: RequestMetadata
    media_type: Literal["text/plain"]
    content: str = Field(min_length=1, max_length=120_000)
    source_label: str | None = Field(default=None, max_length=128)


class DocumentParseResponse(ApiModel):
    metadata: ResponseMetadata
    segments: list[Segment]
    character_count: int = Field(ge=1, le=120_000)


class FactExtractionRequest(ApiModel):
    metadata: RequestMetadata
    segments: list[Segment] = Field(min_length=1, max_length=100)
    allowed_field_names: list[str] = Field(min_length=1, max_length=100)


class ExtractedFact(ApiModel):
    fact_id: str
    field_name: str
    value: str
    source_segment_ids: list[str]


class FactExtractionResponse(ApiModel):
    metadata: ResponseMetadata
    facts: list[ExtractedFact]


class Metric(ApiModel):
    metric_code: str = Field(min_length=1, max_length=64, pattern=r"^[A-Z0-9_]+$")
    value: str = Field(pattern=r"^-?[0-9]+(?:\.[0-9]+)?$")
    limit: str = Field(pattern=r"^-?[0-9]+(?:\.[0-9]+)?$")


class Evidence(ApiModel):
    evidence_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9._-]+$")
    summary: str = Field(min_length=1, max_length=2_000)


class ReasonJudgmentRequest(ApiModel):
    metadata: RequestMetadata
    metrics: list[Metric] = Field(min_length=1, max_length=100)
    evidence: list[Evidence] = Field(default_factory=list, max_length=100)

    @model_validator(mode="after")
    def evidence_ids_are_unique(self) -> Self:
        evidence_ids = [item.evidence_id for item in self.evidence]
        if len(evidence_ids) != len(set(evidence_ids)):
            raise ValueError("evidenceId must be unique")
        return self


class ReasonJudgmentResponse(ApiModel):
    metadata: ResponseMetadata
    over_limit: bool
    reason_summary: str
    cited_evidence_ids: list[str]


class ReportFact(ApiModel):
    field_name: str = Field(min_length=1, max_length=100)
    value: str = Field(min_length=1, max_length=4_000)


class JudgmentInput(ApiModel):
    over_limit: bool
    reason_summary: str = Field(min_length=1, max_length=4_000)
    cited_evidence_ids: list[str] = Field(default_factory=list, max_length=100)


class ReportCompositionRequest(ApiModel):
    metadata: RequestMetadata
    facts: list[ReportFact] = Field(default_factory=list, max_length=200)
    judgment: JudgmentInput
    allowed_evidence_ids: list[str] = Field(default_factory=list, max_length=100)

    @model_validator(mode="after")
    def citations_are_whitelisted(self) -> Self:
        if not set(self.judgment.cited_evidence_ids).issubset(self.allowed_evidence_ids):
            raise ValueError("judgment contains evidence outside allowedEvidenceIds")
        return self


class ReportSections(ApiModel):
    title: str
    situation: str
    analysis: str
    rectification: str


class ReportCompositionResponse(ApiModel):
    metadata: ResponseMetadata
    sections: ReportSections
    cited_evidence_ids: list[str]


EditableField = Literal["title", "situation", "analysis", "rectification"]


class CorrectionInterpretationRequest(ApiModel):
    metadata: RequestMetadata
    instruction: str = Field(min_length=1, max_length=4_000)
    editable_fields: list[EditableField] = Field(min_length=1, max_length=4)


class CorrectionOperation(ApiModel):
    field: EditableField
    replacement: str


class CorrectionInterpretationResponse(ApiModel):
    metadata: ResponseMetadata
    operations: list[CorrectionOperation]


AssistanceIntent = Literal["ASK", "EDIT", "IMAGE_ANALYSIS"]


class ReportImageInput(ApiModel):
    file_name: str = Field(min_length=1, max_length=255)
    media_type: Literal["image/png", "image/jpeg"]
    base64_data: str = Field(min_length=4, max_length=14_000_000)

    @model_validator(mode="after")
    def image_is_valid(self) -> Self:
        try:
            content = base64.b64decode(self.base64_data, validate=True)
        except (binascii.Error, ValueError) as exception:
            raise ValueError("base64Data must be valid base64") from exception
        if not content or len(content) > 10 * 1024 * 1024:
            raise ValueError("decoded image must be between 1 byte and 10 MiB")
        if self.media_type == "image/png" and not content.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("PNG signature is invalid")
        if self.media_type == "image/jpeg" and not content.startswith(b"\xff\xd8\xff"):
            raise ValueError("JPEG signature is invalid")
        return self


class ReportAssistanceRequest(ApiModel):
    metadata: RequestMetadata
    intent: AssistanceIntent
    instruction: str = Field(min_length=1, max_length=4_000)
    current_sections: ReportSections
    facts: list[ReportFact] = Field(default_factory=list, max_length=200)
    images: list[ReportImageInput] = Field(default_factory=list, max_length=10)
    allowed_evidence_ids: list[str] = Field(default_factory=list, max_length=100)

    @model_validator(mode="after")
    def images_match_intent(self) -> Self:
        if self.intent == "IMAGE_ANALYSIS" and not self.images:
            raise ValueError("IMAGE_ANALYSIS requires at least one image")
        if self.intent != "IMAGE_ANALYSIS" and self.images:
            raise ValueError("images are only accepted for IMAGE_ANALYSIS")
        total = sum(len(base64.b64decode(item.base64_data)) for item in self.images)
        if total > 20 * 1024 * 1024:
            raise ValueError("decoded images must not exceed 20 MiB in total")
        return self


class ReportAssistanceResponse(ApiModel):
    metadata: ResponseMetadata
    answer: str = Field(min_length=1, max_length=10_000)
    updated_sections: ReportSections | None = None
    cited_evidence_ids: list[str] = Field(default_factory=list, max_length=100)


class FieldError(ApiModel):
    field: str
    code: str
    message: str


class AiProblem(ApiModel):
    code: str
    message: str
    trace_id: str
    field_errors: list[FieldError] = Field(default_factory=list)
