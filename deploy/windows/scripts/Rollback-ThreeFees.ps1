#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$DeploymentRoot = 'C:\ProgramData\ThreeFees',
    [string]$HealthUri = 'http://127.0.0.1:8080/actuator/health',
    [switch]$Apply
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Resolve-SafeAbsolutePath -Path $DeploymentRoot -Label 'DeploymentRoot'
Assert-SafeVersion -Version $Version
$releasePath = Get-ReleasePath -DeploymentRoot $root -Version $Version
if (-not (Test-Path -LiteralPath $releasePath -PathType Container)) {
    throw "Rollback release does not exist: $releasePath"
}
$manifest = Test-ReleaseReady -ReleasePath $releasePath

[pscustomobject]@{
    Mode          = $(if ($Apply) { 'Apply' } else { 'PreflightOnly' })
    Version       = [string]$manifest.version
    ReleasePath   = $releasePath
    HealthUri     = $HealthUri
    DataRollback  = 'not performed; database migrations require a separately approved forward-compatible plan'
}
if (-not $Apply) {
    Write-Host 'Rollback preflight completed. No junctions, services, files, or database data were changed.'
    return
}

Assert-Administrator
foreach ($serviceId in $script:ThreeFeesServiceIds) {
    if (-not (Get-Service -Name $serviceId -ErrorAction SilentlyContinue)) {
        throw "Required Windows service is not installed: $serviceId"
    }
}

Switch-CurrentRelease -DeploymentRoot $root -TargetReleasePath $releasePath -HealthUri $HealthUri
Write-Host "Rollback completed: $Version"
