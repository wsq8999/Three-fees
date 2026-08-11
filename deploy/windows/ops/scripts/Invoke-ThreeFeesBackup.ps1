[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ConfigPath,

    [ValidateSet('Daily', 'Weekly')]
    [string]$BackupClass = 'Daily',

    [ValidateSet('Quiesced', 'AppendOnly')]
    [string]$ConsistencyMode,

    [string]$QuiesceMarkerPath,

    [switch]$PromptForDatabasePassword,

    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ops.Common.ps1')

function Get-ValidatedQuiesceMarker {
    param(
        [Parameter(Mandatory = $true)][string]$MarkerPath,
        [Parameter(Mandatory = $true)][hashtable]$OperationsConfig
    )

    $path = Resolve-OpsAbsolutePath -Path $MarkerPath -Label 'QuiesceMarkerPath'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw 'The external write-quiesce marker does not exist.'
    }
    Assert-OpsNotReparsePoint -Path $path -Label 'Quiesce marker'
    $marker = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([int]$marker.schemaVersion -ne 1 -or [string]$marker.purpose -ne 'ThreeFeesWritesQuiesced') {
        throw 'The external write-quiesce marker is invalid.'
    }
    $expectedDeploymentRoot = Resolve-OpsAbsolutePath -Path ([string]$OperationsConfig.Paths.DeploymentRoot) -Label 'DeploymentRoot'
    $markerDeploymentRoot = Resolve-OpsAbsolutePath -Path ([string]$marker.deploymentRoot) -Label 'Marker deploymentRoot'
    if (-not [string]::Equals($expectedDeploymentRoot, $markerDeploymentRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The write-quiesce marker belongs to a different deployment.'
    }
    $quiescedAt = [DateTime]::Parse([string]$marker.writesQuiescedAtUtc).ToUniversalTime()
    $expiresAt = [DateTime]::Parse([string]$marker.expiresAtUtc).ToUniversalTime()
    $now = [DateTime]::UtcNow
    if ($quiescedAt -gt $now -or $expiresAt -le $now) {
        throw 'The write-quiesce marker is not currently valid.'
    }
    if (($expiresAt - $quiescedAt).TotalMinutes -gt [double]$OperationsConfig.Backup.QuiesceMarkerMaxWindowMinutes) {
        throw 'The write-quiesce marker window exceeds the configured maximum.'
    }
    return [pscustomobject]@{ path = $path; writesQuiescedAtUtc = $quiescedAt; expiresAtUtc = $expiresAt }
}

$config = Get-OperationsConfig -ConfigPath $ConfigPath
$backupRoot = Assert-OpsRootMarker -RootPath ([string]$config.Paths.BackupRoot) -Purpose BackupRoot
$fileRoot = Resolve-OpsAbsolutePath -Path ([string]$config.Paths.FileRoot) -Label 'FileRoot'
$dumpExecutable = Resolve-OpsAbsolutePath -Path ([string]$config.Database.DumpExecutable) -Label 'Database.DumpExecutable'
if (-not (Test-Path -LiteralPath $fileRoot -PathType Container)) {
    throw 'The business file root does not exist.'
}
Assert-OpsNotReparsePoint -Path $fileRoot -Label 'FileRoot'
$sourceReparsePoint = Get-ChildItem -LiteralPath $fileRoot -Recurse -Force -ErrorAction Stop | Where-Object {
    ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
} | Select-Object -First 1
if ($null -ne $sourceReparsePoint) {
    throw 'The business file tree contains a reparse point; refusing an ambiguous snapshot.'
}
if (-not (Test-Path -LiteralPath $dumpExecutable -PathType Leaf)) {
    throw 'The configured mysqldump executable does not exist.'
}

if ([string]::IsNullOrWhiteSpace($ConsistencyMode)) {
    $ConsistencyMode = [string]$config.Backup.DefaultConsistencyMode
}
$quiesceMarker = $null
if ($ConsistencyMode -eq 'AppendOnly') {
    if (-not [bool]$config.Backup.AppendOnlyFileContractApproved) {
        throw 'Append-only file consistency is not approved; use a valid external write-quiesce marker.'
    }
}
elseif ($Apply) {
    if ([string]::IsNullOrWhiteSpace($QuiesceMarkerPath)) {
        throw 'Quiesced backup requires an external write-quiesce marker when -Apply is used.'
    }
    $quiesceMarker = Get-ValidatedQuiesceMarker -MarkerPath $QuiesceMarkerPath -OperationsConfig $config
}

$classRoot = Join-Path $backupRoot $BackupClass.ToLowerInvariant()
$stagingRoot = Join-Path $backupRoot '.staging'
Assert-OpsPathInside -ParentPath $backupRoot -ChildPath $classRoot -Label 'Backup class root'
Assert-OpsPathInside -ParentPath $backupRoot -ChildPath $stagingRoot -Label 'Backup staging root'

$plan = [pscustomobject]@{
    action = if ($Apply) { 'CreateBackup' } else { 'WouldCreateBackup' }
    backupClass = $BackupClass
    consistencyMode = $ConsistencyMode
    sourceFileRoot = $fileRoot
    databaseHost = [string]$config.Database.Host
    databaseName = [string]$config.Database.Name
    backupRoot = $backupRoot
}
if (-not $Apply) {
    $plan
    return
}

foreach ($requiredDirectory in @($classRoot, $stagingRoot)) {
    if (-not (Test-Path -LiteralPath $requiredDirectory -PathType Container)) {
        throw 'Managed backup subdirectories are missing; rerun root initialization.'
    }
    Assert-OpsNotReparsePoint -Path $requiredDirectory -Label 'Managed backup directory'
}

$usernameName = [string]$config.Database.BackupUsernameEnvironmentVariable
$passwordName = [string]$config.Database.BackupPasswordEnvironmentVariable
$databaseUsername = Get-OpsEnvironmentValue -Name $usernameName
if ([string]::IsNullOrWhiteSpace($databaseUsername)) {
    throw "Required database username environment variable is not set: $usernameName"
}
$databasePassword = Get-OpsSecret -EnvironmentVariableName $passwordName -Prompt 'Backup database password' -AllowPrompt:$PromptForDatabasePassword

$backupId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + ([Guid]::NewGuid().ToString('N').Substring(0, 8))
$stagingPath = Join-Path $stagingRoot ($backupId + '.partial')
$finalPath = Join-Path $classRoot $backupId
Assert-OpsPathInside -ParentPath $stagingRoot -ChildPath $stagingPath -Label 'Backup staging path'
Assert-OpsPathInside -ParentPath $classRoot -ChildPath $finalPath -Label 'Backup final path'
if ((Test-Path -LiteralPath $stagingPath) -or (Test-Path -LiteralPath $finalPath)) {
    throw 'Generated backup path already exists.'
}

$startedAt = [DateTime]::UtcNow
[void](New-Item -ItemType Directory -Path (Join-Path $stagingPath 'database') -Force)
[void](New-Item -ItemType Directory -Path (Join-Path $stagingPath 'files') -Force)
$dumpPath = Join-Path $stagingPath 'database\database.sql'
$dumpErrorPath = Join-Path $stagingPath 'database\mysqldump.stderr.log'
$copyLogPath = Join-Path $stagingPath 'file-copy.log'
$databaseSnapshotStartedAt = [DateTime]::UtcNow
$previousMySqlPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', [EnvironmentVariableTarget]::Process)
try {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $databasePassword, [EnvironmentVariableTarget]::Process)
    $dumpArguments = @(
        '--protocol=TCP',
        "--host=$($config.Database.Host)",
        "--port=$($config.Database.Port)",
        "--user=$databaseUsername",
        '--single-transaction', '--quick', '--skip-lock-tables', '--routines', '--events', '--triggers',
        '--hex-blob', '--set-gtid-purged=OFF', '--default-character-set=utf8mb4', '--no-tablespaces',
        [string]$config.Database.Name
    )
    $dumpExitCode = Invoke-OpsExternalProcess -FilePath $dumpExecutable -Arguments $dumpArguments -StandardOutputPath $dumpPath -StandardErrorPath $dumpErrorPath
    if ($dumpExitCode -ne 0) {
        throw "Database snapshot failed with exit code $dumpExitCode. Review the protected backup staging log."
    }
    if (-not (Test-Path -LiteralPath $dumpPath -PathType Leaf) -or (Get-Item -LiteralPath $dumpPath).Length -eq 0) {
        throw 'Database snapshot produced an empty dump.'
    }
    $databaseSnapshotCompletedAt = [DateTime]::UtcNow

    [void](Invoke-OpsRobocopy -SourcePath $fileRoot -DestinationPath (Join-Path $stagingPath 'files') -LogPath $copyLogPath)
    $filesSnapshotCompletedAt = [DateTime]::UtcNow
    if ($ConsistencyMode -eq 'Quiesced') {
        $quiesceMarker = Get-ValidatedQuiesceMarker -MarkerPath $QuiesceMarkerPath -OperationsConfig $config
        if ($quiesceMarker.writesQuiescedAtUtc -gt $databaseSnapshotStartedAt -or $quiesceMarker.expiresAtUtc -lt $filesSnapshotCompletedAt) {
            throw 'The write-quiesce window did not cover the complete database and file snapshot.'
        }
    }

    $entries = @(New-OpsPayloadManifestEntries -PayloadRoot $stagingPath | Where-Object {
        $_.path -notin @('file-copy.log', 'file-copy.log.stderr')
    })
    if ($entries.Count -eq 0) {
        throw 'Backup payload manifest is empty.'
    }
    $manifestPath = Join-Path $stagingPath $script:OpsBackupManifestName
    Write-OpsJsonFile -Path $manifestPath -InputObject ([ordered]@{
        schemaVersion = 1
        backupId = $backupId
        entries = $entries
    })
    $completedAt = [DateTime]::UtcNow
    $payloadBytes = [long]0
    foreach ($entry in $entries) { $payloadBytes += [long]$entry.length }
    $metadataPath = Join-Path $stagingPath $script:OpsBackupMetadataName
    Write-OpsJsonFile -Path $metadataPath -InputObject ([ordered]@{
        schemaVersion = 1
        backupId = $backupId
        backupClass = $BackupClass
        status = 'Complete'
        consistencyMode = $ConsistencyMode
        startedAtUtc = $startedAt.ToString('o')
        databaseSnapshotStartedAtUtc = $databaseSnapshotStartedAt.ToString('o')
        databaseSnapshotCompletedAtUtc = $databaseSnapshotCompletedAt.ToString('o')
        filesSnapshotCompletedAtUtc = $filesSnapshotCompletedAt.ToString('o')
        recoveryPointUtc = $databaseSnapshotStartedAt.ToString('o')
        completedAtUtc = $completedAt.ToString('o')
        consistencyWindowSeconds = [math]::Round(($filesSnapshotCompletedAt - $databaseSnapshotStartedAt).TotalSeconds, 3)
        database = [ordered]@{ host = [string]$config.Database.Host; port = [int]$config.Database.Port; name = [string]$config.Database.Name }
        sourceFileRoot = $fileRoot
        payloadFileCount = $entries.Count
        payloadBytes = $payloadBytes
    })
    $readyPath = Join-Path $stagingPath $script:OpsBackupReadyName
    Write-OpsJsonFile -Path $readyPath -InputObject ([ordered]@{
        schemaVersion = 1
        backupId = $backupId
        completedAtUtc = $completedAt.ToString('o')
        metadataSha256 = (Get-FileHash -LiteralPath $metadataPath -Algorithm SHA256).Hash.ToLowerInvariant()
        manifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    })
    Move-Item -LiteralPath $stagingPath -Destination $finalPath
    [void](Test-OpsBackupArtifact -BackupRoot $backupRoot -ArtifactPath $finalPath -VerifyPayload)
    [pscustomobject]@{
        action = 'BackupCreated'
        backupId = $backupId
        backupClass = $BackupClass
        consistencyMode = $ConsistencyMode
        completedAtUtc = $completedAt.ToString('o')
        path = $finalPath
    }
}
finally {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousMySqlPassword, [EnvironmentVariableTarget]::Process)
    $databasePassword = $null
}
