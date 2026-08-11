[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string]$ConfigPath,

    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ops.Common.ps1')

$config = Get-OperationsConfig -ConfigPath $ConfigPath
$backupRoot = Assert-OpsRootMarker -RootPath ([string]$config.Paths.BackupRoot) -Purpose BackupRoot
$classPolicies = @(
    [pscustomobject]@{ backupClass = 'Daily'; keep = [int]$config.Backup.DailyRetentionCount },
    [pscustomobject]@{ backupClass = 'Weekly'; keep = [int]$config.Backup.WeeklyRetentionCount }
)

foreach ($policy in $classPolicies) {
    $validArtifacts = @()
    foreach ($directory in @(Get-OpsBackupArtifactDirectories -BackupRoot $backupRoot -BackupClass $policy.backupClass)) {
        try {
            $metadata = Test-OpsBackupArtifact -BackupRoot $backupRoot -ArtifactPath $directory.FullName
            $validArtifacts += [pscustomobject]@{
                path = $directory.FullName
                backupId = [string]$metadata.backupId
                completedAtUtc = [DateTime]::Parse([string]$metadata.completedAtUtc).ToUniversalTime()
            }
        }
        catch {
            Write-Warning "Invalid backup artifact was left untouched: $($directory.Name)"
        }
    }

    $ordered = @($validArtifacts | Sort-Object completedAtUtc -Descending)
    $candidates = @($ordered | Select-Object -Skip $policy.keep)
    if ($candidates.Count -eq 0) {
        [pscustomobject]@{ action = 'NoRetentionChange'; backupClass = $policy.backupClass; validCount = $ordered.Count; keep = $policy.keep }
        continue
    }

    foreach ($candidate in $candidates) {
        if (-not $Apply) {
            [pscustomobject]@{ action = 'WouldRemove'; backupClass = $policy.backupClass; backupId = $candidate.backupId; path = $candidate.path }
            continue
        }
        if ($PSCmdlet.ShouldProcess($candidate.path, 'Remove expired validated backup')) {
            Remove-OpsManagedBackupArtifact -BackupRoot $backupRoot -ArtifactPath $candidate.path -Apply
        }
    }
}
