$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$python = Join-Path $root "backend\.venv\Scripts\python.exe"

if (-not (Test-Path $python)) {
    throw "Backend virtual environment not found. Run 'uv sync --all-groups' in backend first."
}

# Run backend lint, schema drift detection, and automated tests.
Push-Location (Join-Path $root "backend")
try {
    & $python -m ruff check app tests migrations ..\scripts\export_openapi.py ..\scripts\import_historical_reports.py ..\scripts\backfill_historical_report_sites.py ..\scripts\build_historical_cases.py
    if ($LASTEXITCODE -ne 0) { throw "Backend lint failed." }

    & $python -m alembic check
    if ($LASTEXITCODE -ne 0) { throw "Database schema drift check failed." }

    & $python -m pytest
    if ($LASTEXITCODE -ne 0) { throw "Backend tests failed." }
}
finally {
    Pop-Location
}

# Regenerate the shared API contract before validating the frontend build.
& (Join-Path $PSScriptRoot "generate-api-client.ps1")
Push-Location (Join-Path $root "frontend")
try {
    pnpm build
    if ($LASTEXITCODE -ne 0) { throw "Frontend production build failed." }
}
finally {
    Pop-Location
}
