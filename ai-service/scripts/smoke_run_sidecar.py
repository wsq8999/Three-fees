from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
PYTHON = ROOT / ".venv-image-analysis" / "Scripts" / "python.exe"


def wait_ready() -> None:
    for _ in range(40):
        time.sleep(0.5)
        try:
            with urlopen("http://127.0.0.1:8100/docs", timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            continue
    raise RuntimeError("AI sidecar did not become ready on 8100")


def main() -> int:
    env = os.environ.copy()
    env.setdefault("AI_SERVICE_TOKEN", "test-token-for-three-fees")
    env.setdefault("AI_PROVIDER", "fake")
    env.setdefault("AI_INITIALIZE_CHECKPOINTS", "false")
    env.setdefault("PYTHONIOENCODING", "utf-8")
    process = subprocess.Popen(
        [
            str(PYTHON),
            "-m",
            "uvicorn",
            "app.image_analysis_main:app",
            "--host",
            "127.0.0.1",
            "--port",
            "8100",
        ],
        cwd=ROOT,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    try:
        wait_ready()
        smoke = subprocess.run(
            [str(PYTHON), str(ROOT / "scripts" / "smoke_report_image_analysis.py")],
            cwd=ROOT,
            env=env,
            check=False,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
        )
        output_path = ROOT / ".runtime-smoke-report-image-analysis.json"
        output_path.write_text(smoke.stdout, encoding="utf-8")
        print(f"smoke output written: {output_path}")
        if smoke.returncode != 0:
            print(smoke.stderr, file=sys.stderr)
        return smoke.returncode
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()


if __name__ == "__main__":
    raise SystemExit(main())
