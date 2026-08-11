[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ConfigPath,

    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ops.Common.ps1')

$config = Get-OperationsConfig -ConfigPath $ConfigPath
$backupRoot = Assert-OpsManagedRootCandidate -Path ([string]$config.Paths.BackupRoot) -Label 'BackupRoot'
$restoreRoot = Assert-OpsManagedRootCandidate -Path ([string]$config.Paths.RestoreDrillRoot) -Label 'RestoreDrillRoot'
$protectedRoots = @(
    [string]$config.Paths.DeploymentRoot,
    [string]$config.Paths.FileRoot,
    [string]$config.Paths.LogRoot
)

if (Test-OpsPathsOverlap -FirstPath $backupRoot -SecondPath $restoreRoot) {
    throw 'BackupRoot and RestoreDrillRoot must be separate, non-nested directories.'
}
foreach ($protectedRoot in $protectedRoots) {
    if (Test-OpsPathsOverlap -FirstPath $backupRoot -SecondPath $protectedRoot) {
        throw 'BackupRoot cannot overlap a production path.'
    }
    if (Test-OpsPathsOverlap -FirstPath $restoreRoot -SecondPath $protectedRoot) {
        throw 'RestoreDrillRoot cannot overlap a production path.'
    }
}

$rootPlans = @(
    [pscustomobject]@{ path = $backupRoot; purpose = 'BackupRoot'; childDirectories = @('daily', 'weekly', '.staging') },
    [pscustomobject]@{ path = $restoreRoot; purpose = 'RestoreDrillRoot'; childDirectories = @() }
)

foreach ($plan in $rootPlans) {
    Assert-OpsNotReparsePoint -Path $plan.path -Label $plan.purpose
    $markerPath = Get-OpsRootMarkerPath -RootPath $plan.path
    if (Test-Path -LiteralPath $plan.path -PathType Container) {
        if (Test-Path -LiteralPath $markerPath -PathType Leaf) {
            [void](Assert-OpsRootMarker -RootPath $plan.path -Purpose $plan.purpose)
        }
        else {
            $existingItems = @(Get-ChildItem -LiteralPath $plan.path -Force)
            if ($existingItems.Count -gt 0) {
                throw "$($plan.purpose) exists and is not empty or managed; refusing to adopt it."
            }
        }
    }

    if (-not $Apply) {
        [pscustomobject]@{ action = 'WouldInitializeOrValidate'; purpose = $plan.purpose; path = $plan.path }
        continue
    }

    if (-not (Test-Path -LiteralPath $plan.path -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $plan.path)
    }
    if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
        Write-OpsJsonFile -Path $markerPath -InputObject ([ordered]@{
            schemaVersion = 1
            purpose = $plan.purpose
            rootPath = $plan.path
            createdAtUtc = Get-OpsUtcTimestamp
        })
    }
    foreach ($childDirectory in $plan.childDirectories) {
        $childPath = Join-Path $plan.path $childDirectory
        Assert-OpsPathInside -ParentPath $plan.path -ChildPath $childPath -Label 'Managed child directory'
        if (-not (Test-Path -LiteralPath $childPath -PathType Container)) {
            [void](New-Item -ItemType Directory -Path $childPath)
        }
    }
    [void](Assert-OpsRootMarker -RootPath $plan.path -Purpose $plan.purpose)
    [pscustomobject]@{ action = 'InitializedOrValidated'; purpose = $plan.purpose; path = $plan.path }
}
