[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ConfigPath,

    [Parameter(Mandatory = $true)]
    [string]$BackupPath,

    [Parameter(Mandatory = $true)]
    [string]$SandboxDatabaseName,

    [string]$SandboxDatabaseHost = '127.0.0.1',

    [int]$SandboxDatabasePort = 3306,

    [string]$IsolationAcknowledgement,

    [DateTime]$IncidentTimeUtc = [DateTime]::UtcNow,

    [switch]$PromptForDatabasePassword,

    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ops.Common.ps1')

function Invoke-MySqlQuery {
    param(
        [Parameter(Mandatory = $true)][string]$ClientPath,
        [Parameter(Mandatory = $true)][string[]]$ConnectionArguments,
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$ErrorPath
    )

    $arguments = @($ConnectionArguments) + @('--batch', '--skip-column-names', "--execute=$Query")
    $exitCode = Invoke-OpsExternalProcess -FilePath $ClientPath -Arguments $arguments -StandardOutputPath $OutputPath -StandardErrorPath $ErrorPath
    if ($exitCode -ne 0) {
        throw "Sandbox database query failed with exit code $exitCode."
    }
    $numericLine = @(Get-Content -LiteralPath $OutputPath -ErrorAction Stop | Where-Object { $_ -match '^\s*[0-9]+\s*$' } | Select-Object -Last 1)
    if ($numericLine.Count -ne 1) {
        throw 'Sandbox database query did not return one numeric result.'
    }
    return [long]([string]$numericLine[0]).Trim()
}

$config = Get-OperationsConfig -ConfigPath $ConfigPath
$backupRoot = Assert-OpsRootMarker -RootPath ([string]$config.Paths.BackupRoot) -Purpose BackupRoot
$restoreRoot = Assert-OpsRootMarker -RootPath ([string]$config.Paths.RestoreDrillRoot) -Purpose RestoreDrillRoot
$metadata = Test-OpsBackupArtifact -BackupRoot $backupRoot -ArtifactPath $BackupPath -VerifyPayload
$artifactPath = Resolve-OpsAbsolutePath -Path $BackupPath -Label 'BackupPath'

$databasePrefix = [string]$config.Restore.DatabaseNamePrefix
if ([string]::IsNullOrWhiteSpace($databasePrefix) -or $databasePrefix -notmatch '^[a-z][a-z0-9_]{4,48}_$') {
    throw 'Restore database prefix is unsafe.'
}
if ($SandboxDatabaseName -notmatch ('^' + [regex]::Escape($databasePrefix) + '[a-z0-9_]{1,32}$')) {
    throw 'Sandbox database name does not use the required isolated restore prefix.'
}
if ($SandboxDatabaseHost -notin @($config.Restore.AllowedDatabaseHosts)) {
    throw 'Sandbox database host is not in the explicit restore allowlist.'
}
if ($SandboxDatabasePort -lt 1 -or $SandboxDatabasePort -gt 65535) {
    throw 'Sandbox database port is invalid.'
}
$clientPath = Resolve-OpsAbsolutePath -Path ([string]$config.Database.ClientExecutable) -Label 'Database.ClientExecutable'
if (-not (Test-Path -LiteralPath $clientPath -PathType Leaf)) {
    throw 'The configured mysql client executable does not exist.'
}
$dumpPath = Join-Path $artifactPath 'database\database.sql'
if (-not (Test-Path -LiteralPath $dumpPath -PathType Leaf)) {
    throw 'The validated backup has no database dump.'
}
$unsafeDatabaseStatement = Select-String -LiteralPath $dumpPath -Pattern '(?i)^\s*(CREATE|DROP)\s+DATABASE\b|^\s*USE\s+[`"]' -Encoding UTF8 | Select-Object -First 1
if ($null -ne $unsafeDatabaseStatement) {
    throw 'The database dump contains a cross-database statement and is unsafe for an isolated restore.'
}

$plan = [pscustomobject]@{
    action = if ($Apply) { 'RunIsolatedRestoreDrill' } else { 'WouldRunIsolatedRestoreDrill' }
    backupId = [string]$metadata.backupId
    sandboxDatabaseHost = $SandboxDatabaseHost
    sandboxDatabasePort = $SandboxDatabasePort
    sandboxDatabaseName = $SandboxDatabaseName
    restoreRoot = $restoreRoot
}
if (-not $Apply) {
    $plan
    return
}
if ($IsolationAcknowledgement -ne [string]$config.Restore.IsolationAcknowledgement) {
    throw 'The explicit isolated-restore acknowledgement is required with -Apply.'
}

$usernameName = [string]$config.Database.RestoreUsernameEnvironmentVariable
$passwordName = [string]$config.Database.RestorePasswordEnvironmentVariable
$databaseUsername = Get-OpsEnvironmentValue -Name $usernameName
if ([string]::IsNullOrWhiteSpace($databaseUsername)) {
    throw "Required restore username environment variable is not set: $usernameName"
}
$databasePassword = Get-OpsSecret -EnvironmentVariableName $passwordName -Prompt 'Isolated restore database password' -AllowPrompt:$PromptForDatabasePassword

$drillId = 'drill-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + ([Guid]::NewGuid().ToString('N').Substring(0, 8))
$drillPath = Join-Path $restoreRoot $drillId
Assert-OpsPathInside -ParentPath $restoreRoot -ChildPath $drillPath -Label 'Restore drill path'
if (Test-Path -LiteralPath $drillPath) {
    throw 'Generated restore drill path already exists.'
}
[void](New-Item -ItemType Directory -Path $drillPath)
[void](New-Item -ItemType Directory -Path (Join-Path $drillPath 'files'))

$startedAt = [DateTime]::UtcNow
$resultPath = Join-Path $drillPath 'restore-drill-result.json'
$previousMySqlPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', [EnvironmentVariableTarget]::Process)
$resultWritten = $false
try {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $databasePassword, [EnvironmentVariableTarget]::Process)
    $connectionArguments = @(
        '--protocol=TCP',
        "--host=$SandboxDatabaseHost",
        "--port=$SandboxDatabasePort",
        "--user=$databaseUsername",
        "--database=$SandboxDatabaseName"
    )
    $emptyQueryOutput = Join-Path $drillPath 'database-empty-check.out'
    $emptyQueryError = Join-Path $drillPath 'database-empty-check.stderr.log'
    $tableCountBefore = Invoke-MySqlQuery -ClientPath $clientPath -ConnectionArguments $connectionArguments -Query 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()' -OutputPath $emptyQueryOutput -ErrorPath $emptyQueryError
    if ($tableCountBefore -ne 0) {
        throw 'The isolated restore database is not empty; refusing to overwrite it.'
    }

    $importError = Join-Path $drillPath 'database-import.stderr.log'
    $importOutput = Join-Path $drillPath 'database-import.out'
    $importExitCode = Invoke-OpsExternalProcess -FilePath $clientPath -Arguments $connectionArguments -StandardInputPath $dumpPath -StandardOutputPath $importOutput -StandardErrorPath $importError
    if ($importExitCode -ne 0) {
        throw "Sandbox database import failed with exit code $importExitCode."
    }

    $tableQueryOutput = Join-Path $drillPath 'database-table-check.out'
    $tableQueryError = Join-Path $drillPath 'database-table-check.stderr.log'
    $tableCountAfter = Invoke-MySqlQuery -ClientPath $clientPath -ConnectionArguments $connectionArguments -Query 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()' -OutputPath $tableQueryOutput -ErrorPath $tableQueryError
    if ($tableCountAfter -le 0) {
        throw 'The database import completed without restoring any tables.'
    }

    $fileCopyLog = Join-Path $drillPath 'file-restore.log'
    [void](Invoke-OpsRobocopy -SourcePath (Join-Path $artifactPath 'files') -DestinationPath (Join-Path $drillPath 'files') -LogPath $fileCopyLog)
    $manifest = Get-Content -LiteralPath (Join-Path $artifactPath $script:OpsBackupManifestName) -Raw -Encoding UTF8 | ConvertFrom-Json
    $restoredFileCount = 0
    foreach ($entry in @($manifest.entries | Where-Object { ([string]$_.path).StartsWith('files/', [StringComparison]::OrdinalIgnoreCase) })) {
        $relativeFilePath = ([string]$entry.path).Substring('files/'.Length).Replace('/', '\')
        $restoredPath = [IO.Path]::GetFullPath((Join-Path (Join-Path $drillPath 'files') $relativeFilePath))
        Assert-OpsPathInside -ParentPath (Join-Path $drillPath 'files') -ChildPath $restoredPath -Label 'Restored file'
        if (-not (Test-Path -LiteralPath $restoredPath -PathType Leaf)) {
            throw 'A restored business file is missing.'
        }
        $restoredItem = Get-Item -LiteralPath $restoredPath
        $restoredHash = (Get-FileHash -LiteralPath $restoredPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ([long]$entry.length -ne [long]$restoredItem.Length -or $restoredHash -ne ([string]$entry.sha256).ToLowerInvariant()) {
            throw 'A restored business file failed integrity verification.'
        }
        $restoredFileCount++
    }

    $completedAt = [DateTime]::UtcNow
    $rtoHours = [math]::Round(($completedAt - $startedAt).TotalHours, 6)
    $recoveryPoint = [DateTime]::Parse([string]$metadata.recoveryPointUtc).ToUniversalTime()
    $rpoHours = [math]::Round(($IncidentTimeUtc.ToUniversalTime() - $recoveryPoint).TotalHours, 6)
    $result = [ordered]@{
        schemaVersion = 1
        drillId = $drillId
        status = 'Succeeded'
        backupId = [string]$metadata.backupId
        startedAtUtc = $startedAt.ToString('o')
        completedAtUtc = $completedAt.ToString('o')
        incidentTimeUtc = $IncidentTimeUtc.ToUniversalTime().ToString('o')
        recoveryPointUtc = $recoveryPoint.ToString('o')
        rpoHours = $rpoHours
        rtoHours = $rtoHours
        rpoPassed = $rpoHours -ge 0 -and $rpoHours -le [double]$config.Thresholds.RpoHours
        rtoPassed = $rtoHours -le [double]$config.Thresholds.RtoHours
        sandboxDatabase = [ordered]@{ host = $SandboxDatabaseHost; port = $SandboxDatabasePort; name = $SandboxDatabaseName; restoredTableCount = $tableCountAfter }
        restoredFileCount = $restoredFileCount
    }
    Write-OpsJsonFile -Path $resultPath -InputObject $result
    Write-OpsJsonFile -Path (Join-Path $drillPath 'RESTORE_READY.json') -InputObject ([ordered]@{
        schemaVersion = 1
        drillId = $drillId
        completedAtUtc = $completedAt.ToString('o')
        resultSha256 = (Get-FileHash -LiteralPath $resultPath -Algorithm SHA256).Hash.ToLowerInvariant()
    })
    $resultWritten = $true
    [pscustomobject]$result
}
catch {
    if (-not $resultWritten) {
        Write-OpsJsonFile -Path $resultPath -InputObject ([ordered]@{
            schemaVersion = 1
            drillId = $drillId
            status = 'Failed'
            backupId = [string]$metadata.backupId
            startedAtUtc = $startedAt.ToString('o')
            completedAtUtc = [DateTime]::UtcNow.ToString('o')
            failureCategory = 'RestoreDrillFailed'
        })
    }
    throw
}
finally {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousMySqlPassword, [EnvironmentVariableTarget]::Process)
    $databasePassword = $null
}
