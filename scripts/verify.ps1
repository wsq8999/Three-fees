[CmdletBinding()]
param(
    [ValidateSet('all', 'repository', 'backend', 'frontend')]
    [string]$Scope = 'all',

    [switch]$InstallDependencies
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot

function Invoke-Checked {
    param(
        [Parameter(Mandatory)]
        [string]$Name,

        [Parameter(Mandatory)]
        [scriptblock]$Command
    )

    Write-Host "`n==> $Name" -ForegroundColor Cyan
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

function Test-Scope {
    param([string]$Expected)
    return $Scope -eq 'all' -or $Scope -eq $Expected
}

Push-Location $repositoryRoot
try {
    if (Test-Scope 'repository') {
        Invoke-Checked 'Git whitespace check' { git diff --check }
        $policyScript = Join-Path $PSScriptRoot 'Test-RepositoryPolicy.ps1'
        Invoke-Checked 'Repository secret/runtime-file policy' { & $policyScript }
        $fieldCatalogScript = Join-Path $PSScriptRoot 'Test-FieldCatalogBaseline.ps1'
        Invoke-Checked 'Requirements field catalog baseline' { & $fieldCatalogScript }
    }

    if (Test-Scope 'backend') {
        $backendPath = Join-Path $repositoryRoot 'backend'
        $mavenWrapper = Join-Path $backendPath 'mvnw.cmd'
        if (-not (Test-Path -LiteralPath $mavenWrapper)) {
            throw "Maven wrapper not found: $mavenWrapper"
        }

        Push-Location $backendPath
        try {
            Invoke-Checked 'Backend verification' { & $mavenWrapper verify }
        }
        finally {
            Pop-Location
        }
    }

    if (Test-Scope 'frontend') {
        $frontendPath = Join-Path $repositoryRoot 'frontend'
        if (-not (Test-Path -LiteralPath (Join-Path $frontendPath 'package.json'))) {
            throw "Frontend package.json not found: $frontendPath"
        }

        Push-Location $frontendPath
        try {
            if ($InstallDependencies) {
                Invoke-Checked 'Frontend dependency installation' {
                    corepack pnpm install --frozen-lockfile
                }
            }
            Invoke-Checked 'Frontend code lint' { corepack pnpm lint }
            Invoke-Checked 'Frontend style lint' { corepack pnpm lint:styles }
            Invoke-Checked 'Frontend formatting check' { corepack pnpm format:check }
            Invoke-Checked 'Frontend typecheck' { corepack pnpm typecheck }
            Invoke-Checked 'Frontend unit tests' { corepack pnpm test:unit --run }
            Invoke-Checked 'Frontend production build' { corepack pnpm build }
        }
        finally {
            Pop-Location
        }
    }

}
finally {
    Pop-Location
}

Write-Host "`nVerification completed successfully." -ForegroundColor Green
