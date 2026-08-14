$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$python = Join-Path $root "backend\.venv\Scripts\python.exe"

if (-not (Test-Path $python)) {
    throw "Backend virtual environment not found. Run 'uv sync --all-groups' in backend first."
}

# Use the project Python directly to avoid Windows application-control launcher issues.
Push-Location (Join-Path $root "backend")
try {
    & $python (Join-Path $root "scripts\export_openapi.py")
    if ($LASTEXITCODE -ne 0) { throw "OpenAPI export failed." }
}
finally {
    Pop-Location
}
