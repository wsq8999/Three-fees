from __future__ import annotations

import base64
import json
import os
import sys
from urllib.request import Request, urlopen


TOKEN = os.getenv("AI_SERVICE_TOKEN", "test-token-for-three-fees")
BASE_URL = os.getenv("AI_SERVICE_BASE_URL", "http://127.0.0.1:8100")


def main() -> None:
    png = base64.b64encode(
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
        b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89"
    ).decode("ascii")
    body = {
        "metadata": {"jobId": "job-001", "traceId": "trace-001"},
        "billing_point_code": "ZDBZD-NJ-001",
        "period": "2025-03",
        "content_html": (
            "<h1>报告</h1><h2>二、排查分析</h2><p><br /></p>"
            "<h2>三、整改小结</h2>"
        ),
        "instruction": "补充图片分析",
        "facts": [{"field_name": "所属城市", "value": "南京"}],
        "images": [
            {
                "file_name": "site.png",
                "media_type": "image/png",
                "base64_data": png,
            }
        ],
    }
    request = Request(
        f"{BASE_URL.rstrip('/')}/api/v1/report-image-analysis",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urlopen(request, timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if "二、排查分析" not in payload.get("updated_content_html", ""):
        raise SystemExit("updated_content_html 未包含排查分析章节")
    print(json.dumps(payload, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.exit(main())
