$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

& (Join-Path $PSScriptRoot "export-openapi.ps1")
Push-Location (Join-Path $root "frontend")
try {
    pnpm generate:api
    if ($LASTEXITCODE -ne 0) { throw "Frontend API type generation failed." }
}
finally {
    Pop-Location
}
