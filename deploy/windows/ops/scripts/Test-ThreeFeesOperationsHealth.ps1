[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ConfigPath,

    [switch]$SkipServiceChecks,

    [switch]$SkipHttpChecks,

    [switch]$AsJson,

    [switch]$FailOnUnhealthy
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ops.Common.ps1')

function Add-HealthResult {
    param([object]$Result)
    $script:healthResults += $Result
}

function Test-HttpHealthEndpoint {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Uri
    )
    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 10
        $body = $response.Content | ConvertFrom-Json
        if ([int]$response.StatusCode -eq 200 -and [string]$body.status -eq 'UP') {
            return New-OpsCheckResult -Check "http.$Name" -Status Pass -Message 'Health endpoint returned UP.' -Value 200
        }
        return New-OpsCheckResult -Check "http.$Name" -Status Fail -Message 'Health endpoint did not return the expected status.' -Value ([int]$response.StatusCode)
    }
    catch {
        return New-OpsCheckResult -Check "http.$Name" -Status Fail -Message 'Health endpoint request failed; inspect protected service logs.' -Value $null
    }
}

$config = Get-OperationsConfig -ConfigPath $ConfigPath
$script:healthResults = @()
$checkedAt = [DateTime]::UtcNow

foreach ($pathName in @('DeploymentRoot', 'FileRoot', 'LogRoot', 'BackupRoot', 'RestoreDrillRoot')) {
    $path = Resolve-OpsAbsolutePath -Path ([string]$config.Paths[$pathName]) -Label $pathName
    $exists = Test-Path -LiteralPath $path -PathType Container
    Add-HealthResult (New-OpsCheckResult -Check "path.$pathName" -Status $(if ($exists) { 'Pass' } else { 'Fail' }) -Message $(if ($exists) { 'Directory exists.' } else { 'Directory is missing.' }) -Value $path)
}

try {
    [void](Assert-OpsRootMarker -RootPath ([string]$config.Paths.BackupRoot) -Purpose BackupRoot)
    Add-HealthResult (New-OpsCheckResult -Check 'backup.rootMarker' -Status Pass -Message 'Backup root marker is valid.' -Value $null)
}
catch {
    Add-HealthResult (New-OpsCheckResult -Check 'backup.rootMarker' -Status Fail -Message 'Backup root is not safely initialized.' -Value $null)
}

if (-not $SkipServiceChecks) {
    foreach ($serviceKey in @('Api', 'Worker', 'Ai')) {
        $serviceConfig = $config.Services[$serviceKey]
        $serviceName = [string]$serviceConfig.Name
        try {
            $service = Get-CimInstance -ClassName Win32_Service -Filter "Name='$serviceName'"
            if ($null -eq $service) { throw 'Missing service' }
            $stateMatches = [string]$service.State -eq [string]$serviceConfig.ExpectedState
            $modeMatches = [string]$service.StartMode -eq [string]$serviceConfig.ExpectedStartMode
            Add-HealthResult (New-OpsCheckResult -Check "service.$serviceKey.state" -Status $(if ($stateMatches) { 'Pass' } else { 'Fail' }) -Message $(if ($stateMatches) { 'Service state matches the contract.' } else { 'Service state differs from the contract.' }) -Value ([string]$service.State))
            Add-HealthResult (New-OpsCheckResult -Check "service.$serviceKey.startMode" -Status $(if ($modeMatches) { 'Pass' } else { 'Fail' }) -Message $(if ($modeMatches) { 'Service start mode matches the contract.' } else { 'Service start mode differs from the contract.' }) -Value ([string]$service.StartMode))
        }
        catch {
            Add-HealthResult (New-OpsCheckResult -Check "service.$serviceKey" -Status Fail -Message 'Service could not be inspected.' -Value $serviceName)
        }

        $port = [int]$serviceConfig.ListenPort
        if ($port -gt 0) {
            try {
                $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction Stop)
                $unsafeListeners = @($listeners | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') })
                if ($listeners.Count -eq 0) {
                    Add-HealthResult (New-OpsCheckResult -Check "listener.$serviceKey" -Status Fail -Message 'Expected loopback listener is absent.' -Value $port)
                }
                elseif ($unsafeListeners.Count -gt 0) {
                    Add-HealthResult (New-OpsCheckResult -Check "listener.$serviceKey" -Status Fail -Message 'Service has a non-loopback listener.' -Value $port)
                }
                else {
                    Add-HealthResult (New-OpsCheckResult -Check "listener.$serviceKey" -Status Pass -Message 'Listener is restricted to loopback.' -Value $port)
                }
            }
            catch {
                Add-HealthResult (New-OpsCheckResult -Check "listener.$serviceKey" -Status Warning -Message 'TCP listener could not be inspected.' -Value $port)
            }
        }
    }
}

if (-not $SkipHttpChecks) {
    Add-HealthResult (Test-HttpHealthEndpoint -Name 'api' -Uri ([string]$config.Services.Api.HealthUri))
    Add-HealthResult (Test-HttpHealthEndpoint -Name 'ai' -Uri ([string]$config.Services.Ai.HealthUri))
}

$uniqueRoots = @(
    [string]$config.Paths.DeploymentRoot,
    [string]$config.Paths.BackupRoot,
    [string]$config.Paths.RestoreDrillRoot
) | ForEach-Object { [IO.Path]::GetPathRoot((Resolve-OpsAbsolutePath -Path $_ -Label 'Disk path')) } | Sort-Object -Unique
foreach ($driveRoot in $uniqueRoots) {
    try {
        $drive = New-Object IO.DriveInfo($driveRoot)
        $freeGb = [math]::Round($drive.AvailableFreeSpace / 1GB, 2)
        $freePercent = [math]::Round(($drive.AvailableFreeSpace / $drive.TotalSize) * 100, 2)
        $passes = $freeGb -ge [double]$config.Thresholds.MinimumFreeGigabytes -and $freePercent -ge [double]$config.Thresholds.MinimumFreePercent
        Add-HealthResult (New-OpsCheckResult -Check "disk.$($drive.Name)" -Status $(if ($passes) { 'Pass' } else { 'Fail' }) -Message $(if ($passes) { 'Disk free space is above thresholds.' } else { 'Disk free space is below a threshold.' }) -Value ([pscustomobject]@{ freeGigabytes = $freeGb; freePercent = $freePercent }))
    }
    catch {
        Add-HealthResult (New-OpsCheckResult -Check "disk.$driveRoot" -Status Warning -Message 'Disk capacity could not be inspected.' -Value $null)
    }
}

$logRoot = Resolve-OpsAbsolutePath -Path ([string]$config.Paths.LogRoot) -Label 'LogRoot'
$logStats = Get-OpsDirectoryStats -Path $logRoot
$logGigabytes = [math]::Round(([long]$logStats.totalBytes / 1GB), 3)
Add-HealthResult (New-OpsCheckResult -Check 'logs.totalSize' -Status $(if ($logGigabytes -le [double]$config.Thresholds.MaximumLogGigabytes) { 'Pass' } else { 'Fail' }) -Message 'Aggregate log size was measured.' -Value $logGigabytes)

foreach ($stream in @('api', 'ai', 'worker')) {
    $streamPath = Join-Path $logRoot $stream
    $stats = Get-OpsDirectoryStats -Path $streamPath
    $required = $stream -in @('api', 'ai')
    if ($null -eq $stats.newestWriteUtc) {
        Add-HealthResult (New-OpsCheckResult -Check "logs.$stream.freshness" -Status $(if ($required) { 'Fail' } else { 'Pass' }) -Message $(if ($required) { 'Required log stream has no files.' } else { 'Worker logs are optional while the worker is stopped.' }) -Value $null)
        continue
    }
    $ageMinutes = [math]::Round(($checkedAt - $stats.newestWriteUtc).TotalMinutes, 2)
    $fresh = -not $required -or $ageMinutes -le [double]$config.Thresholds.MaximumRequiredLogAgeMinutes
    Add-HealthResult (New-OpsCheckResult -Check "logs.$stream.freshness" -Status $(if ($fresh) { 'Pass' } else { 'Fail' }) -Message 'Newest log age was measured.' -Value $ageMinutes)
}

$recentCutoff = $checkedAt.AddHours(-[double]$config.Thresholds.RecentLogWindowHours)
$recentLogFiles = @(Get-ChildItem -LiteralPath $logRoot -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTimeUtc -ge $recentCutoff } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 30)
$errorCount = 0
$sensitiveIndicatorCount = 0
foreach ($logFile in $recentLogFiles) {
    $tail = @(Get-Content -LiteralPath $logFile.FullName -Tail 1000 -ErrorAction SilentlyContinue)
    $errorCount += @($tail | Select-String -Pattern '(?i)("level"\s*:\s*"(ERROR|FATAL)"|\b(ERROR|FATAL)\b)').Count
    $sensitiveIndicatorCount += @($tail | Select-String -Pattern '(?i)(password|authorization|api[_-]?key|token)\s*[=:]\s*(?!\[?redacted\]?|<redacted>|null)').Count
}
$errorStatus = if ($errorCount -le [int]$config.Thresholds.MaximumRecentErrorCount) { 'Pass' } else { 'Warning' }
Add-HealthResult (New-OpsCheckResult -Check 'logs.recentErrors' -Status $errorStatus -Message 'Only the aggregate recent error count is reported.' -Value $errorCount)
Add-HealthResult (New-OpsCheckResult -Check 'logs.sensitiveIndicators' -Status $(if ($sensitiveIndicatorCount -eq 0) { 'Pass' } else { 'Fail' }) -Message 'Potential secret-bearing log lines were counted but not printed.' -Value $sensitiveIndicatorCount)

try {
    $backups = @()
    foreach ($backupClass in @('Daily', 'Weekly')) {
        foreach ($directory in @(Get-OpsBackupArtifactDirectories -BackupRoot ([string]$config.Paths.BackupRoot) -BackupClass $backupClass)) {
            try {
                $metadata = Test-OpsBackupArtifact -BackupRoot ([string]$config.Paths.BackupRoot) -ArtifactPath $directory.FullName
                $backups += $metadata
            }
            catch { }
        }
    }
    if ($backups.Count -eq 0) {
        Add-HealthResult (New-OpsCheckResult -Check 'backup.freshness' -Status Fail -Message 'No valid complete backup exists.' -Value $null)
    }
    else {
        $latest = $backups | Sort-Object { [DateTime]::Parse([string]$_.recoveryPointUtc) } -Descending | Select-Object -First 1
        $ageHours = [math]::Round(($checkedAt - [DateTime]::Parse([string]$latest.recoveryPointUtc).ToUniversalTime()).TotalHours, 2)
        $status = if ($ageHours -le [double]$config.Thresholds.BackupWarningHours) { 'Pass' } elseif ($ageHours -le [double]$config.Thresholds.RpoHours) { 'Warning' } else { 'Fail' }
        Add-HealthResult (New-OpsCheckResult -Check 'backup.freshness' -Status $status -Message 'Latest validated recovery point age was measured.' -Value $ageHours)
    }
}
catch {
    Add-HealthResult (New-OpsCheckResult -Check 'backup.freshness' -Status Fail -Message 'Backup freshness could not be validated.' -Value $null)
}

$summary = [pscustomobject]@{
    schemaVersion = 1
    checkedAtUtc = $checkedAt.ToString('o')
    overallStatus = if (@($script:healthResults | Where-Object status -eq 'Fail').Count -gt 0) { 'Fail' } elseif (@($script:healthResults | Where-Object status -eq 'Warning').Count -gt 0) { 'Warning' } else { 'Pass' }
    checks = $script:healthResults
}
if ($AsJson) { $summary | ConvertTo-Json -Depth 8 } else { $summary }
if ($FailOnUnhealthy -and $summary.overallStatus -ne 'Pass') { exit 2 }
