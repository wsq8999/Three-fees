[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ConfigPath,

    [DateTime]$AsOfUtc = [DateTime]::UtcNow,

    [switch]$AsJson,

    [switch]$FailOnBreach
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ops.Common.ps1')

$config = Get-OperationsConfig -ConfigPath $ConfigPath
$backupRoot = Assert-OpsRootMarker -RootPath ([string]$config.Paths.BackupRoot) -Purpose BackupRoot
$restoreRoot = Assert-OpsRootMarker -RootPath ([string]$config.Paths.RestoreDrillRoot) -Purpose RestoreDrillRoot
$asOf = $AsOfUtc.ToUniversalTime()

$validBackups = @()
foreach ($backupClass in @('Daily', 'Weekly')) {
    foreach ($directory in @(Get-OpsBackupArtifactDirectories -BackupRoot $backupRoot -BackupClass $backupClass)) {
        try {
            $metadata = Test-OpsBackupArtifact -BackupRoot $backupRoot -ArtifactPath $directory.FullName
            $validBackups += $metadata
        }
        catch { }
    }
}

$latestBackup = $validBackups | Sort-Object { [DateTime]::Parse([string]$_.recoveryPointUtc) } -Descending | Select-Object -First 1
$rpoHours = $null
$rpoPass = $false
if ($null -ne $latestBackup) {
    $rpoHours = [math]::Round(($asOf - [DateTime]::Parse([string]$latestBackup.recoveryPointUtc).ToUniversalTime()).TotalHours, 3)
    $rpoPass = $rpoHours -ge 0 -and $rpoHours -le [double]$config.Thresholds.RpoHours
}

$successfulDrills = @()
foreach ($resultFile in @(Get-ChildItem -LiteralPath $restoreRoot -Recurse -File -Filter 'restore-drill-result.json' -ErrorAction SilentlyContinue)) {
    try {
        $result = Get-Content -LiteralPath $resultFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        $readyPath = Join-Path $resultFile.DirectoryName 'RESTORE_READY.json'
        if (-not (Test-Path -LiteralPath $readyPath -PathType Leaf)) { continue }
        $ready = Get-Content -LiteralPath $readyPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $resultHash = (Get-FileHash -LiteralPath $resultFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        if ([int]$result.schemaVersion -eq 1 -and [string]$result.status -eq 'Succeeded' -and
            [int]$ready.schemaVersion -eq 1 -and [string]$ready.drillId -eq [string]$result.drillId -and
            $resultHash -eq ([string]$ready.resultSha256).ToLowerInvariant()) {
            $successfulDrills += $result
        }
    }
    catch { }
}
$latestDrill = $successfulDrills | Sort-Object { [DateTime]::Parse([string]$_.completedAtUtc) } -Descending | Select-Object -First 1
$rtoHours = $null
$drillAgeDays = $null
$rtoPass = $false
if ($null -ne $latestDrill) {
    $rtoHours = [double]$latestDrill.rtoHours
    $drillAgeDays = [math]::Round(($asOf - [DateTime]::Parse([string]$latestDrill.completedAtUtc).ToUniversalTime()).TotalDays, 3)
    $rtoPass = $rtoHours -le [double]$config.Thresholds.RtoHours -and $drillAgeDays -ge 0 -and $drillAgeDays -le [double]$config.Restore.MaximumDrillAgeDays
}

$resultSummary = [pscustomobject]@{
    schemaVersion = 1
    checkedAtUtc = $asOf.ToString('o')
    status = if ($rpoPass -and $rtoPass) { 'Pass' } else { 'Fail' }
    rpo = [pscustomobject]@{
        thresholdHours = [double]$config.Thresholds.RpoHours
        actualHours = $rpoHours
        passed = $rpoPass
        latestBackupId = if ($null -ne $latestBackup) { [string]$latestBackup.backupId } else { $null }
    }
    rto = [pscustomobject]@{
        thresholdHours = [double]$config.Thresholds.RtoHours
        actualHours = $rtoHours
        passed = $rtoPass
        drillAgeDays = $drillAgeDays
        maximumDrillAgeDays = [double]$config.Restore.MaximumDrillAgeDays
        latestDrillId = if ($null -ne $latestDrill) { [string]$latestDrill.drillId } else { $null }
    }
}
if ($AsJson) { $resultSummary | ConvertTo-Json -Depth 6 } else { $resultSummary }
if ($FailOnBreach -and $resultSummary.status -ne 'Pass') { exit 2 }
