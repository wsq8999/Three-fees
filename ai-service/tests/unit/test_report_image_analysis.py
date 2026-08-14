from __future__ import annotations

import base64

from fastapi.testclient import TestClient

from app.image_analysis_main import create_app


TOKEN = "test-token-for-three-fees"
PNG_1X1 = base64.b64encode(
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
    b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\nIDATx\x9cc\x00\x01"
    b"\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
).decode("ascii")


def test_report_image_analysis_uses_zip_service_adapter(monkeypatch) -> None:
    monkeypatch.setenv("AI_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("AI_PROVIDER", "fake")
    monkeypatch.setenv("AI_INITIALIZE_CHECKPOINTS", "false")

    body = {
        "metadata": {
            "jobId": "job-001",
            "traceId": "trace-001",
        },
        "billing_point_code": "ZDBZD-NJ-001",
        "period": "2025-03",
        "content_html": "<h1>报告</h1><h2>二、排查分析</h2><p><br /></p><h2>三、整改小结</h2>",
        "instruction": "补充图片分析",
        "facts": [{"field_name": "所属城市", "value": "南京"}],
        "images": [
            {
                "file_name": "site.png",
                "media_type": "image/png",
                "base64_data": PNG_1X1,
            }
        ],
    }

    with TestClient(create_app()) as client:
        response = client.post(
            "/api/v1/report-image-analysis",
            json=body,
            headers={"Authorization": f"Bearer {TOKEN}"},
        )

    assert response.status_code == 200
    payload = response.json()
    assert payload["metadata"]["jobId"] == "job-001"
    assert "updated_content_html" in payload
    assert "二、排查分析" in payload["updated_content_html"]
    assert "图片分析" in payload["answer"]


def test_report_image_analysis_requires_token(monkeypatch) -> None:
    monkeypatch.setenv("AI_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("AI_PROVIDER", "fake")
    monkeypatch.setenv("AI_INITIALIZE_CHECKPOINTS", "false")

    with TestClient(create_app()) as client:
        response = client.post("/api/v1/report-image-analysis", json={})

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AI_SERVICE_UNAUTHORIZED"
