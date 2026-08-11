[CmdletBinding()]
param(
    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Ops.Common.ps1')

if (-not $Apply) {
    [pscustomobject]@{
        action = 'WouldRunTemporaryBehaviorTests'
        temporaryBase = [IO.Path]::GetTempPath()
        realDatabaseConnection = $false
        systemConfigurationChanges = $false
    }
    return
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('ThreeFeesOpsTest-' + [Guid]::NewGuid().ToString('N'))
$testRoot = [IO.Path]::GetFullPath($testRoot)
$testMarker = Join-Path $testRoot '.three-fees-ops-test-root'
$previousEnvironment = @{}
$testEnvironmentNames = @(
    'THREE_FEES_TEST_BACKUP_DB_USERNAME',
    'THREE_FEES_TEST_BACKUP_DB_PASSWORD',
    'THREE_FEES_TEST_RESTORE_DB_USERNAME',
    'THREE_FEES_TEST_RESTORE_DB_PASSWORD',
    'THREE_FEES_TEST_MYSQL_STATE'
)
$passes = @()

function Add-BehaviorPass { param([string]$Message) $script:passes += $Message; Write-Output "PASS: $Message" }
function Assert-Behavior {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Behavior assertion failed: $Message" }
    Add-BehaviorPass $Message
}

function ConvertTo-TestPsd1String {
    param([string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

try {
    [void](New-Item -ItemType Directory -Path $testRoot)
    [IO.File]::WriteAllText($testMarker, 'temporary test root', (New-Object Text.UTF8Encoding($false)))
    $deploymentRoot = Join-Path $testRoot 'deployment'
    $fileRoot = Join-Path $deploymentRoot 'shared\files'
    $logRoot = Join-Path $deploymentRoot 'shared\logs'
    $backupRoot = Join-Path $testRoot 'backups'
    $restoreRoot = Join-Path $testRoot 'restore-drills'
    foreach ($directory in @($fileRoot, (Join-Path $logRoot 'api'), (Join-Path $logRoot 'ai'), (Join-Path $logRoot 'worker'))) {
        [void](New-Item -ItemType Directory -Path $directory -Force)
    }
    [IO.File]::WriteAllText((Join-Path $fileRoot 'sample-business-file.txt'), 'immutable test payload', (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $logRoot 'api\api.log'), 'INFO traceId=test ready', (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $logRoot 'ai\ai.log'), 'INFO traceId=test ready', (New-Object Text.UTF8Encoding($false)))

    $fakeSource = @'
using System;
using System.IO;
using System.Linq;
public static class FakeMySqlClient {
    public static int Main(string[] args) {
        string executable = Path.GetFileName(Environment.GetCommandLineArgs()[0]);
        if (executable.IndexOf("mysqldump", StringComparison.OrdinalIgnoreCase) >= 0) {
            Console.WriteLine("-- deterministic fake dump");
            Console.WriteLine("CREATE TABLE app_user (id BIGINT PRIMARY KEY);");
            return 0;
        }
        string statePath = Environment.GetEnvironmentVariable("THREE_FEES_TEST_MYSQL_STATE");
        bool isQuery = args.Any(value => value.StartsWith("--execute=", StringComparison.Ordinal));
        if (isQuery) {
            Console.WriteLine(!String.IsNullOrEmpty(statePath) && File.Exists(statePath) ? "3" : "0");
            return 0;
        }
        string input = Console.In.ReadToEnd();
        if (String.IsNullOrWhiteSpace(input) || String.IsNullOrEmpty(statePath)) { return 12; }
        File.WriteAllText(statePath, "imported");
        return 0;
    }
}
'@
    $compilerRoot = Join-Path $testRoot 'fake-tools'
    [void](New-Item -ItemType Directory -Path $compilerRoot)
    $fakeDumpExecutable = Join-Path $compilerRoot 'fake-mysqldump.exe'
    $fakeClientExecutable = Join-Path $compilerRoot 'fake-mysql.exe'
    Add-Type -TypeDefinition $fakeSource -Language CSharp -OutputAssembly $fakeDumpExecutable -OutputType ConsoleApplication
    Copy-Item -LiteralPath $fakeDumpExecutable -Destination $fakeClientExecutable

    $configPath = Join-Path $testRoot 'operations.test.psd1'
    $configContent = @"
@{
    SchemaVersion = 1
    Paths = @{
        DeploymentRoot = $(ConvertTo-TestPsd1String $deploymentRoot)
        FileRoot = $(ConvertTo-TestPsd1String $fileRoot)
        LogRoot = $(ConvertTo-TestPsd1String $logRoot)
        BackupRoot = $(ConvertTo-TestPsd1String $backupRoot)
        RestoreDrillRoot = $(ConvertTo-TestPsd1String $restoreRoot)
    }
    Services = @{
        Api = @{ Name='three-fees-api'; ExpectedState='Running'; ExpectedStartMode='Auto'; HealthUri='http://127.0.0.1:8080/actuator/health'; ListenPort=8080 }
        Worker = @{ Name='three-fees-worker'; ExpectedState='Stopped'; ExpectedStartMode='Manual'; ListenPort=0 }
        Ai = @{ Name='three-fees-ai'; ExpectedState='Running'; ExpectedStartMode='Auto'; HealthUri='http://127.0.0.1:8100/health'; ListenPort=8100 }
    }
    Database = @{
        Host='127.0.0.1'; Port=3306; Name='three_fees'
        DumpExecutable=$(ConvertTo-TestPsd1String $fakeDumpExecutable)
        ClientExecutable=$(ConvertTo-TestPsd1String $fakeClientExecutable)
        BackupUsernameEnvironmentVariable='THREE_FEES_TEST_BACKUP_DB_USERNAME'
        BackupPasswordEnvironmentVariable='THREE_FEES_TEST_BACKUP_DB_PASSWORD'
        RestoreUsernameEnvironmentVariable='THREE_FEES_TEST_RESTORE_DB_USERNAME'
        RestorePasswordEnvironmentVariable='THREE_FEES_TEST_RESTORE_DB_PASSWORD'
    }
    Backup = @{ DefaultConsistencyMode='AppendOnly'; AppendOnlyFileContractApproved=`$true; QuiesceMarkerMaxWindowMinutes=120; DailyRetentionCount=7; WeeklyRetentionCount=4 }
    Restore = @{ DatabaseNamePrefix='three_fees_restore_drill_'; AllowedDatabaseHosts=@('127.0.0.1'); IsolationAcknowledgement='ISOLATED-RESTORE-ONLY'; MaximumDrillAgeDays=90 }
    Thresholds = @{ RpoHours=24; RtoHours=4; BackupWarningHours=20; MinimumFreeGigabytes=0; MinimumFreePercent=0; MaximumLogGigabytes=1; MaximumRequiredLogAgeMinutes=30; RecentLogWindowHours=24; MaximumRecentErrorCount=20 }
}
"@
    [IO.File]::WriteAllText($configPath, $configContent, (New-Object Text.UTF8Encoding($false)))

    foreach ($name in $testEnvironmentNames) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process)
    }
    [Environment]::SetEnvironmentVariable('THREE_FEES_TEST_BACKUP_DB_USERNAME', 'backup_test_user', [EnvironmentVariableTarget]::Process)
    [Environment]::SetEnvironmentVariable('THREE_FEES_TEST_BACKUP_DB_PASSWORD', [Guid]::NewGuid().ToString('N'), [EnvironmentVariableTarget]::Process)
    [Environment]::SetEnvironmentVariable('THREE_FEES_TEST_RESTORE_DB_USERNAME', 'restore_test_user', [EnvironmentVariableTarget]::Process)
    [Environment]::SetEnvironmentVariable('THREE_FEES_TEST_RESTORE_DB_PASSWORD', [Guid]::NewGuid().ToString('N'), [EnvironmentVariableTarget]::Process)
    [Environment]::SetEnvironmentVariable('THREE_FEES_TEST_MYSQL_STATE', (Join-Path $testRoot 'fake-mysql-imported.state'), [EnvironmentVariableTarget]::Process)

    $null = & (Join-Path $PSScriptRoot 'Initialize-OperationsRoots.ps1') -ConfigPath $configPath
    Assert-Behavior -Condition (-not (Test-Path -LiteralPath $backupRoot)) -Message 'Root initialization dry-run creates no directory.'
    $null = & (Join-Path $PSScriptRoot 'Initialize-OperationsRoots.ps1') -ConfigPath $configPath -Apply
    Assert-Behavior -Condition ((Test-Path -LiteralPath (Join-Path $backupRoot $script:OpsRootMarkerName)) -and (Test-Path -LiteralPath (Join-Path $restoreRoot $script:OpsRootMarkerName))) -Message 'Apply initializes independently marked backup and restore roots.'

    $null = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesBackup.ps1') -ConfigPath $configPath -BackupClass Daily
    Assert-Behavior -Condition (@(Get-ChildItem -LiteralPath (Join-Path $backupRoot 'daily') -Directory).Count -eq 0) -Message 'Backup dry-run creates no artifact.'

    $missingQuiesceRejected = $false
    try {
        $null = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesBackup.ps1') -ConfigPath $configPath -BackupClass Daily -ConsistencyMode Quiesced -Apply
    }
    catch { $missingQuiesceRejected = $true }
    Assert-Behavior -Condition ($missingQuiesceRejected -and @(Get-ChildItem -LiteralPath (Join-Path $backupRoot 'daily') -Directory).Count -eq 0) -Message 'Quiesced Apply refuses to run without an external stop-write marker.'

    $quiesceMarkerPath = Join-Path $testRoot 'writes-quiesced.json'
    Write-OpsJsonFile -Path $quiesceMarkerPath -InputObject ([ordered]@{
        schemaVersion = 1
        purpose = 'ThreeFeesWritesQuiesced'
        deploymentRoot = $deploymentRoot
        writesQuiescedAtUtc = [DateTime]::UtcNow.AddMinutes(-1).ToString('o')
        expiresAtUtc = [DateTime]::UtcNow.AddMinutes(10).ToString('o')
    })
    $quiescedBackup = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesBackup.ps1') -ConfigPath $configPath -BackupClass Daily -ConsistencyMode Quiesced -QuiesceMarkerPath $quiesceMarkerPath -Apply
    $quiescedMetadata = Test-OpsBackupArtifact -BackupRoot $backupRoot -ArtifactPath $quiescedBackup.path
    Assert-Behavior -Condition ([string]$quiescedMetadata.consistencyMode -eq 'Quiesced') -Message 'A valid stop-write window is recorded in backup metadata.'

    for ($index = 0; $index -lt 7; $index++) {
        $null = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesBackup.ps1') -ConfigPath $configPath -BackupClass Daily -Apply
    }
    for ($index = 0; $index -lt 5; $index++) {
        $null = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesBackup.ps1') -ConfigPath $configPath -BackupClass Weekly -Apply
    }
    Assert-Behavior -Condition (@(Get-OpsBackupArtifactDirectories -BackupRoot $backupRoot -BackupClass Daily).Count -eq 8) -Message 'Eight simulated daily backups were created with proofs.'
    Assert-Behavior -Condition (@(Get-OpsBackupArtifactDirectories -BackupRoot $backupRoot -BackupClass Weekly).Count -eq 5) -Message 'Five simulated weekly backups were created with proofs.'

    $null = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesBackupRetention.ps1') -ConfigPath $configPath
    Assert-Behavior -Condition (@(Get-OpsBackupArtifactDirectories -BackupRoot $backupRoot -BackupClass Daily).Count -eq 8) -Message 'Retention dry-run removes nothing.'
    $null = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesBackupRetention.ps1') -ConfigPath $configPath -Apply -Confirm:$false
    Assert-Behavior -Condition (@(Get-OpsBackupArtifactDirectories -BackupRoot $backupRoot -BackupClass Daily).Count -eq 7) -Message 'Retention keeps exactly seven daily backups.'
    Assert-Behavior -Condition (@(Get-OpsBackupArtifactDirectories -BackupRoot $backupRoot -BackupClass Weekly).Count -eq 4) -Message 'Retention keeps exactly four weekly backups.'

    $outsideRejected = $false
    try { $null = Remove-OpsManagedBackupArtifact -BackupRoot $backupRoot -ArtifactPath $fileRoot -Apply -Confirm:$false }
    catch { $outsideRejected = $true }
    Assert-Behavior -Condition ($outsideRejected -and (Test-Path -LiteralPath (Join-Path $fileRoot 'sample-business-file.txt'))) -Message 'Guarded deletion rejects a path outside the marked backup root.'

    $latestDaily = Get-OpsBackupArtifactDirectories -BackupRoot $backupRoot -BackupClass Daily | Sort-Object Name -Descending | Select-Object -First 1
    $null = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesRestoreDrill.ps1') -ConfigPath $configPath -BackupPath $latestDaily.FullName -SandboxDatabaseName 'three_fees_restore_drill_test'
    Assert-Behavior -Condition (@(Get-ChildItem -LiteralPath $restoreRoot -Directory).Count -eq 0) -Message 'Restore dry-run creates no sandbox run.'
    $restoreResult = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesRestoreDrill.ps1') -ConfigPath $configPath -BackupPath $latestDaily.FullName -SandboxDatabaseName 'three_fees_restore_drill_test' -IsolationAcknowledgement 'ISOLATED-RESTORE-ONLY' -Apply
    Assert-Behavior -Condition ([string]$restoreResult.status -eq 'Succeeded' -and [long]$restoreResult.sandboxDatabase.restoredTableCount -eq 3) -Message 'Isolated restore drill verifies database import and file hashes.'

    $nonEmptyDatabaseRejected = $false
    try {
        $null = & (Join-Path $PSScriptRoot 'Invoke-ThreeFeesRestoreDrill.ps1') -ConfigPath $configPath -BackupPath $latestDaily.FullName -SandboxDatabaseName 'three_fees_restore_drill_nonempty' -IsolationAcknowledgement 'ISOLATED-RESTORE-ONLY' -Apply
    }
    catch { $nonEmptyDatabaseRejected = $true }
    Assert-Behavior -Condition $nonEmptyDatabaseRejected -Message 'Restore drill refuses a non-empty sandbox database without deleting it.'

    $healthResult = & (Join-Path $PSScriptRoot 'Test-ThreeFeesOperationsHealth.ps1') -ConfigPath $configPath -SkipServiceChecks -SkipHttpChecks
    Assert-Behavior -Condition ([string]$healthResult.overallStatus -eq 'Pass') -Message 'Read-only path, log, disk and backup health checks pass on the isolated fixture.'

    $objectives = & (Join-Path $PSScriptRoot 'Test-ThreeFeesRecoveryObjectives.ps1') -ConfigPath $configPath
    Assert-Behavior -Condition ([string]$objectives.status -eq 'Pass') -Message 'Fresh backup and successful drill satisfy RPO 24h and RTO 4h baselines.'
    Write-Output "Operations behavior tests: $($passes.Count) PASS"
}
finally {
    foreach ($name in $testEnvironmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], [EnvironmentVariableTarget]::Process)
    }
    if (Test-Path -LiteralPath $testRoot) {
        $tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        $safeTestRoot = [IO.Path]::GetFullPath($testRoot)
        if (-not $safeTestRoot.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) -or
            (Split-Path -Path $safeTestRoot -Leaf) -notmatch '^ThreeFeesOpsTest-[0-9a-f]{32}$' -or
            -not (Test-Path -LiteralPath $testMarker -PathType Leaf)) {
            throw 'Refusing to clean an unverified temporary test root.'
        }
        Remove-Item -LiteralPath $safeTestRoot -Recurse -Force
    }
}
