#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseArchive,

    [string]$DeploymentRoot = 'C:\ProgramData\ThreeFees',
    [string]$HealthUri = 'http://127.0.0.1:8080/actuator/health',
    [switch]$Apply
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Resolve-SafeAbsolutePath -Path $DeploymentRoot -Label 'DeploymentRoot'
$archivePath = [System.IO.Path]::GetFullPath($ReleaseArchive)
$manifest = Test-ReleaseArchive -ArchivePath $archivePath
$version = [string]$manifest.version
$releasePath = Get-ReleasePath -DeploymentRoot $root -Version $version

if (-not (Test-Path -LiteralPath (Join-Path $root 'current'))) {
    throw "No current deployment exists. Use Install-ThreeFees.ps1 first: $root"
}
if (Test-Path -LiteralPath $releasePath) {
    throw "Release version already exists and will not be overwritten: $releasePath"
}
foreach ($serviceId in $script:ThreeFeesServiceIds) {
    if (-not (Get-Service -Name $serviceId -ErrorAction SilentlyContinue) -and $Apply) {
        throw "Required Windows service is not installed: $serviceId"
    }
}

[pscustomobject]@{
    Mode           = $(if ($Apply) { 'Apply' } else { 'PreflightOnly' })
    Version        = $version
    Archive        = $archivePath
    ReleasePath    = $releasePath
    HealthUri      = $HealthUri
    FailurePolicy  = 'restore previous current junction and restart previous services'
}
if (-not $Apply) {
    Write-Host 'Upgrade preflight completed. No files, junctions, or services were changed.'
    return
}

Assert-Administrator
$stagingRoot = Join-Path $root 'staging'
New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
$stagingPath = Join-Path $stagingRoot ([Guid]::NewGuid().ToString('N'))
Assert-PathInside -ParentPath $stagingRoot -ChildPath $stagingPath -Label 'Staging path'

try {
    Expand-VerifiedReleaseArchive -ArchivePath $archivePath -DestinationPath $stagingPath | Out-Null
    Move-Item -LiteralPath $stagingPath -Destination $releasePath
    Write-ReleaseReadyMarker -ReleasePath $releasePath
}
finally {
    if (Test-Path -LiteralPath $stagingPath) {
        Assert-PathInside -ParentPath $stagingRoot -ChildPath $stagingPath -Label 'Staging cleanup path'
        Remove-Item -LiteralPath $stagingPath -Recurse -Force
    }
}

Switch-CurrentRelease -DeploymentRoot $root -TargetReleasePath $releasePath -HealthUri $HealthUri
Write-Host "Upgrade completed: $version"
