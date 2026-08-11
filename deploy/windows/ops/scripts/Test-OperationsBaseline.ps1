[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$opsRoot = Split-Path -Path $PSScriptRoot -Parent
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $opsRoot '..\..\..'))
$configPath = Join-Path $opsRoot 'config\operations.example.psd1'
$requiredFiles = @(
    'README.md',
    'config\operations.example.psd1',
    'scripts\Ops.Common.ps1',
    'scripts\Initialize-OperationsRoots.ps1',
    'scripts\Test-ThreeFeesOperationsHealth.ps1',
    'scripts\Invoke-ThreeFeesBackup.ps1',
    'scripts\Invoke-ThreeFeesBackupRetention.ps1',
    'scripts\Invoke-ThreeFeesRestoreDrill.ps1',
    'scripts\Test-ThreeFeesRecoveryObjectives.ps1',
    'scripts\Test-OperationsScripts.ps1'
)
$failures = @()
$passes = @()

function Add-BaselinePass { param([string]$Message) $script:passes += $Message }
function Add-BaselineFailure { param([string]$Message) $script:failures += $Message }

foreach ($relativePath in $requiredFiles) {
    $fullPath = Join-Path $opsRoot $relativePath
    if (Test-Path -LiteralPath $fullPath -PathType Leaf) { Add-BaselinePass "Required file exists: $relativePath" }
    else { Add-BaselineFailure "Required file is missing: $relativePath" }
}

$runbookPath = Join-Path $repositoryRoot 'docs\operations\OPERATIONS_RUNBOOK.md'
if (Test-Path -LiteralPath $runbookPath -PathType Leaf) { Add-BaselinePass 'Operations runbook exists.' }
else { Add-BaselineFailure 'Operations runbook is missing.' }

$powershellFiles = @(Get-ChildItem -LiteralPath (Join-Path $opsRoot 'scripts') -Filter '*.ps1' -File)
foreach ($file in $powershellFiles) {
    $parseTokens = $null
    $parseProblems = $null
    [void][Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$parseTokens, [ref]$parseProblems)
    if ($parseProblems.Count -eq 0) { Add-BaselinePass "PowerShell parser accepted $($file.Name)." }
    else { Add-BaselineFailure "PowerShell parser rejected $($file.Name): $($parseProblems[0].Message)" }
}

try {
    $config = Import-PowerShellDataFile -LiteralPath $configPath
    if ([int]$config.SchemaVersion -ne 1) { throw 'Unexpected schema version.' }
    if ([int]$config.Backup.DailyRetentionCount -ne 7 -or [int]$config.Backup.WeeklyRetentionCount -ne 4) {
        throw 'Retention contract is not 7 daily plus 4 weekly.'
    }
    if ([string]$config.Backup.DefaultConsistencyMode -ne 'Quiesced' -or [bool]$config.Backup.AppendOnlyFileContractApproved) {
        throw 'Safe consistency defaults have drifted.'
    }
    if ([double]$config.Thresholds.RpoHours -ne 24 -or [double]$config.Thresholds.RtoHours -ne 4) {
        throw 'RPO/RTO thresholds have drifted.'
    }
    foreach ($environmentName in @(
        $config.Database.BackupUsernameEnvironmentVariable,
        $config.Database.BackupPasswordEnvironmentVariable,
        $config.Database.RestoreUsernameEnvironmentVariable,
        $config.Database.RestorePasswordEnvironmentVariable
    )) {
        if ([string]$environmentName -notmatch '^[A-Z][A-Z0-9_]+$') { throw 'A credential source is not an environment variable name.' }
    }
    Add-BaselinePass 'PSD1 config and safety defaults are valid.'
}
catch {
    Add-BaselineFailure "PSD1 config validation failed: $($_.Exception.Message)"
}

$mutatingScripts = @(
    'Initialize-OperationsRoots.ps1',
    'Invoke-ThreeFeesBackup.ps1',
    'Invoke-ThreeFeesBackupRetention.ps1',
    'Invoke-ThreeFeesRestoreDrill.ps1',
    'Test-OperationsScripts.ps1'
)
foreach ($name in $mutatingScripts) {
    $content = Get-Content -LiteralPath (Join-Path $PSScriptRoot $name) -Raw -Encoding UTF8
    if ($content -match '\[switch\]\$Apply' -and $content -match '\$Apply') { Add-BaselinePass "$name has an explicit Apply gate." }
    else { Add-BaselineFailure "$name lacks an explicit Apply gate." }
}

$allScriptText = ($powershellFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
$forbiddenSystemMutations = '(?im)\b(Start|Stop|Restart|New|Set|Remove)-Service\b|\b(Register|Unregister|Set)-ScheduledTask\b|\bschtasks(?:\.exe)?\b|\bsc\.exe\b'
if ($allScriptText -match $forbiddenSystemMutations) { Add-BaselineFailure 'Ops scripts contain a service or scheduled-task mutation command.' }
else { Add-BaselinePass 'No service or scheduled-task mutation command is present.' }

if ($allScriptText -match '(?i)--password(?:=|\s)') { Add-BaselineFailure 'A database password could be passed on the command line.' }
else { Add-BaselinePass 'Database passwords are absent from command-line arguments.' }

$productionScripts = @($powershellFiles | Where-Object {
    $_.Name -notin @('Test-OperationsScripts.ps1', 'Test-OperationsBaseline.ps1', 'Ops.Common.ps1')
})
$unsafeRemove = @($productionScripts | Where-Object { (Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8) -match '(?im)\bRemove-Item\b' })
if ($unsafeRemove.Count -gt 0) { Add-BaselineFailure 'A production entry script bypasses the guarded removal function.' }
else { Add-BaselinePass 'Production entry scripts do not call Remove-Item directly.' }

$commonText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Ops.Common.ps1') -Raw -Encoding UTF8
if ($commonText -match 'function Remove-OpsManagedBackupArtifact' -and
    $commonText -match 'Assert-OpsRootMarker' -and $commonText -match 'Test-OpsBackupArtifact') {
    Add-BaselinePass 'The only production deletion function revalidates the managed root and artifact proof.'
}
else { Add-BaselineFailure 'Guarded backup removal invariants are missing.' }

if ($allScriptText -match '(?i)(mysql|jdbc):\/\/[^\s''"]+:[^\s''"@]+@') { Add-BaselineFailure 'A credential-bearing database URL was found.' }
else { Add-BaselinePass 'No credential-bearing database URL was found.' }

foreach ($pass in $passes) { Write-Output "PASS: $pass" }
foreach ($failure in $failures) { Write-Output "FAIL: $failure" }
Write-Output "Operations baseline: $($passes.Count) PASS, $($failures.Count) FAIL"
if ($failures.Count -gt 0) { exit 1 }
