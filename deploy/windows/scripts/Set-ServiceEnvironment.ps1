#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('three-fees-api', 'three-fees-worker')]
    [string]$ServiceId,

    [string]$DeploymentRoot = 'C:\ProgramData\ThreeFees',
    [string]$DatabaseUrl = 'jdbc:mysql://127.0.0.1:3306/three_fees?characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED',
    [string]$DatabaseUsername,
    [Security.SecureString]$DatabasePassword,
    [bool]$InitialAccountBootstrapEnabled = $false,
    [Security.SecureString]$InitialAccountPassword,
    [bool]$AiEnabled = $false,
    [string]$KimiBaseUrl = 'https://api.moonshot.cn/v1',
    [string]$KimiModel = 'kimi-k3',
    [Security.SecureString]$KimiApiKey,
    [string]$ReportFontPath = 'C:\Windows\Fonts\simhei.ttf',
    [switch]$Restart,
    [switch]$Apply
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Resolve-SafeAbsolutePath -Path $DeploymentRoot -Label 'DeploymentRoot'
$variableNames = @(
    'DB_URL',
    'DB_USERNAME',
    'DB_PASSWORD',
    'APP_FILE_ROOT',
    'REPORT_FONT_PATH',
    'AI_ENABLED',
    'KIMI_BASE_URL',
    'KIMI_MODEL',
    'INITIAL_ACCOUNT_BOOTSTRAP_ENABLED'
)
if ($AiEnabled) {
    $variableNames += 'KIMI_API_KEY'
}
if ($ServiceId -eq 'three-fees-api') {
    $variableNames += 'SESSION_COOKIE_SECURE'
    if ($InitialAccountBootstrapEnabled) {
        $variableNames += 'INITIAL_ACCOUNT_PASSWORD'
    }
}

if ($DatabaseUrl -notmatch '^jdbc:mysql://') {
    throw 'DB_URL must use the jdbc:mysql:// scheme.'
}
if ($DatabaseUrl -match '(?i)jdbc:mysql://[^:/\s]+:[^@\s]+@' -or $DatabaseUrl -match '(?i)(?:password|user)=[^&]+') {
    throw 'DB_URL must not embed database credentials; use DB_USERNAME and DB_PASSWORD.'
}
$resolvedReportFont = Resolve-ReadableReportFont -ReportFontPath $ReportFontPath

[pscustomobject]@{
    Mode          = $(if ($Apply) { 'Apply' } else { 'PreflightOnly' })
    Service       = $ServiceId
    VariableNames = $variableNames -join ', '
    SecretValues  = 'never printed'
}
if (-not $Apply) {
    Write-Host 'Preflight completed. No registry or service changes were made.'
    return
}

Assert-Administrator
if (-not (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue)) {
    throw "Windows service is not installed: $ServiceId"
}

$environment = @()
$plainDatabasePassword = $null
$plainKimiApiKey = $null
$plainInitialAccountPassword = $null
try {
    if ([string]::IsNullOrWhiteSpace($DatabaseUsername)) {
        $DatabaseUsername = Read-Host 'Enter the MySQL application username'
    }
    if ($null -eq $DatabasePassword) {
        $DatabasePassword = Read-Host 'Enter the MySQL application password' -AsSecureString
    }
    $plainDatabasePassword = Convert-SecureStringToPlainText -SecureValue $DatabasePassword
    if ([string]::IsNullOrWhiteSpace($DatabaseUsername) -or [string]::IsNullOrWhiteSpace($plainDatabasePassword)) {
        throw 'MySQL username and password cannot be empty.'
    }

    $environment += @(
        "DB_URL=$DatabaseUrl",
        "DB_USERNAME=$DatabaseUsername",
        "DB_PASSWORD=$plainDatabasePassword",
        ('APP_FILE_ROOT=' + (Join-Path $root 'shared\files')),
        "REPORT_FONT_PATH=$resolvedReportFont",
        ('AI_ENABLED=' + $AiEnabled.ToString().ToLowerInvariant()),
        "KIMI_BASE_URL=$KimiBaseUrl",
        "KIMI_MODEL=$KimiModel"
    )
    if ($AiEnabled) {
        if ($null -eq $KimiApiKey) {
            $KimiApiKey = Read-Host 'Enter the Kimi API key' -AsSecureString
        }
        $plainKimiApiKey = Convert-SecureStringToPlainText -SecureValue $KimiApiKey
        if ([string]::IsNullOrWhiteSpace($plainKimiApiKey)) {
            throw 'Kimi API key cannot be empty when AI is enabled.'
        }
        $environment += "KIMI_API_KEY=$plainKimiApiKey"
    }

    if ($ServiceId -eq 'three-fees-api') {
        $environment += 'SESSION_COOKIE_SECURE=true'
        $environment += ('INITIAL_ACCOUNT_BOOTSTRAP_ENABLED=' + $InitialAccountBootstrapEnabled.ToString().ToLowerInvariant())
        if ($InitialAccountBootstrapEnabled) {
            if ($null -eq $InitialAccountPassword) {
                $InitialAccountPassword = Read-Host 'Enter the temporary initial account password' -AsSecureString
            }
            $plainInitialAccountPassword = Convert-SecureStringToPlainText -SecureValue $InitialAccountPassword
            if ([string]::IsNullOrWhiteSpace($plainInitialAccountPassword)) {
                throw 'Initial account password cannot be empty while bootstrap is enabled.'
            }
            $environment += "INITIAL_ACCOUNT_PASSWORD=$plainInitialAccountPassword"
        }
    }
    else {
        $environment += 'INITIAL_ACCOUNT_BOOTSTRAP_ENABLED=false'
    }

    $registryPath = "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceId"
    New-ItemProperty -LiteralPath $registryPath -Name 'Environment' -PropertyType MultiString -Value $environment -Force | Out-Null
}
finally {
    $plainDatabasePassword = $null
    $plainKimiApiKey = $null
    $plainInitialAccountPassword = $null
    $environment = @()
}

Write-Host "Environment variable names were stored for $ServiceId; values were not printed."
if ($Restart) {
    Restart-Service -Name $ServiceId -Force
}
else {
    Write-Host 'Restart the service during an approved maintenance window for the new environment to take effect.'
}
