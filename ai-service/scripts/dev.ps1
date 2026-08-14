$root = Split-Path -Parent $PSScriptRoot

# Print copy-ready commands for the backend and frontend terminals.
Write-Host "Run these commands in two separate terminals:" -ForegroundColor Cyan
Write-Host "  cd $root\backend; .\.venv\Scripts\python.exe -m uvicorn app.main:app --reload"
Write-Host "  cd $root\frontend; pnpm dev"
