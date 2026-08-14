from __future__ import annotations

import base64
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator


class Metadata(BaseModel):
    contractVersion: str | None = None
    workflowVersion: str | None = None
    jobId: str = Field(min_length=1)
    idempotencyKey: str | None = None
    inputSha256: str | None = None
    traceId: str | None = None


class Fact(BaseModel):
    field_name: str = Field(min_length=1, max_length=100)
    value: str = Field(default="", max_length=2000)


class ImageInput(BaseModel):
    file_name: str = Field(min_length=1, max_length=255)
    media_type: Literal["image/png", "image/jpeg"]
    base64_data: str = Field(min_length=1)

    @field_validator("base64_data")
    @classmethod
    def validate_base64(cls, value: str) -> str:
        try:
            decoded = base64.b64decode(value, validate=True)
        except Exception as exc:
            raise ValueError("图片不是有效的 Base64 数据") from exc
        if len(decoded) == 0 or len(decoded) > 10 * 1024 * 1024:
            raise ValueError("单张图片必须在 1 字节到 10MiB 之间")
        return value


class ReportImageAnalysisRequest(BaseModel):
    metadata: Metadata
    billing_point_code: str = Field(min_length=1, max_length=100)
    period: str = Field(pattern=r"^\d{4}-(0[1-9]|1[0-2])$")
    content_html: str = Field(min_length=1, max_length=2_000_000)
    instruction: str = Field(default="", max_length=4000)
    facts: list[Fact] = Field(default_factory=list, max_length=100)
    images: list[ImageInput] = Field(min_length=1, max_length=10)

    @model_validator(mode="after")
    def validate_total_image_size(self) -> "ReportImageAnalysisRequest":
        total = sum(len(base64.b64decode(image.base64_data)) for image in self.images)
        if total > 20 * 1024 * 1024:
            raise ValueError("图片总大小不能超过 20MiB")
        return self


class ReportImageAnalysisResponse(BaseModel):
    metadata: Metadata
    answer: str
    analysis_text: str
    updated_content_html: str
