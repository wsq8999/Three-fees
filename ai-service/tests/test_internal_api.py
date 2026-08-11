from __future__ import annotations

import secrets

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

SERVICE_TOKEN = secrets.token_urlsafe(32)


def metadata() -> dict[str, str]:
    return {
        "contractVersion": "1.0",
        "workflowVersion": "audit-v1",
        "jobId": "job-01J00000000000000000000000",
        "idempotencyKey": "idem-01J0000000000000000000000",
        "inputSha256": "a" * 64,
        "traceId": "trace-01J000000000000000000000",
    }


def authorized_headers() -> dict[str, str]:
    return {
        "Authorization": f"Bearer {SERVICE_TOKEN}",
        "X-Trace-Id": "trace-01J000000000000000000000",
    }


def client() -> TestClient:
    return TestClient(create_app(service_token=SERVICE_TOKEN))


def test_health_is_local_and_does_not_require_service_token() -> None:
    with client() as test_client:
        response = test_client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "modelProvider": "fake"}


def test_service_refuses_to_start_without_internal_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AI_SERVICE_TOKEN", raising=False)

    with pytest.raises(RuntimeError, match="AI_SERVICE_TOKEN"):
        with TestClient(create_app()):
            pass


def test_internal_endpoint_requires_bearer_token_without_echoing_it() -> None:
    body = {
        "metadata": metadata(),
        "mediaType": "text/plain",
        "content": "安全输入",
    }
    with client() as test_client:
        response = test_client.post(
            "/internal/v1/document-parses",
            headers={"Authorization": "Bearer rejected-sensitive-value"},
            json=body,
        )

    assert response.status_code == 401
    assert response.json()["code"] == "AI_UNAUTHORIZED"
    assert "rejected-sensitive-value" not in response.text


def test_document_parse_is_deterministic_and_stateless() -> None:
    body = {
        "metadata": metadata(),
        "mediaType": "text/plain",
        "content": "第一段。\n\n第二段。",
        "sourceLabel": "pseudonymized-source",
    }
    with client() as test_client:
        first = test_client.post(
            "/internal/v1/document-parses", headers=authorized_headers(), json=body
        )
        second = test_client.post(
            "/internal/v1/document-parses", headers=authorized_headers(), json=body
        )

    assert first.status_code == 200
    assert first.json() == second.json()
    assert first.json()["characterCount"] == len(body["content"])
    assert [segment["segmentId"] for segment in first.json()["segments"]] == [
        "segment-1",
        "segment-2",
    ]


def test_fake_pipeline_only_returns_whitelisted_evidence_and_edit_fields() -> None:
    judgment_body = {
        "metadata": metadata(),
        "metrics": [{"metricCode": "ACTUAL_KWH", "value": "12.50", "limit": "10.00"}],
        "evidence": [
            {"evidenceId": "evidence-1", "summary": "已脱敏依据"},
            {"evidenceId": "evidence-2", "summary": "另一个依据"},
        ],
    }
    correction_body = {
        "metadata": metadata(),
        "instruction": "将整改建议调整为复核计量装置",
        "editableFields": ["rectification"],
    }
    with client() as test_client:
        judgment = test_client.post(
            "/internal/v1/reason-judgments",
            headers=authorized_headers(),
            json=judgment_body,
        )
        correction = test_client.post(
            "/internal/v1/correction-interpretations",
            headers=authorized_headers(),
            json=correction_body,
        )

    assert judgment.status_code == 200
    assert judgment.json()["overLimit"] is True
    assert set(judgment.json()["citedEvidenceIds"]) <= {"evidence-1", "evidence-2"}
    assert correction.status_code == 200
    assert correction.json()["operations"] == [
        {
            "field": "rectification",
            "replacement": correction_body["instruction"],
        }
    ]


def test_invalid_metadata_returns_stable_validation_error() -> None:
    invalid_metadata = metadata()
    invalid_metadata["inputSha256"] = "not-a-sha"
    body = {
        "metadata": invalid_metadata,
        "segments": [{"segmentId": "segment-1", "text": "内容"}],
        "allowedFieldNames": ["billingPointCode"],
    }
    with client() as test_client:
        response = test_client.post(
            "/internal/v1/fact-extractions", headers=authorized_headers(), json=body
        )

    assert response.status_code == 422
    payload = response.json()
    assert payload["code"] == "AI_VALIDATION_FAILED"
    assert payload["traceId"] == authorized_headers()["X-Trace-Id"]
    assert payload["fieldErrors"]
