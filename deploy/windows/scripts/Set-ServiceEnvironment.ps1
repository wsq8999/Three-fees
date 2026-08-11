#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('three-fees-api', 'three-fees-worker', 'three-fees-ai')]
    [string]$ServiceId,

    [string]$DeploymentRoot = 'C:\ProgramData\ThreeFees',
    [string]$DatabaseUrl = 'jdbc:mysql://127.0.0.1:3306/three_fees?characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED',
    [string]$DatabaseUsername,
    [Security.SecureString]$DatabasePassword,
    [bool]$InitialAccountBootstrapEnabled = $false,
    [Security.SecureString]$InitialAccountPassword,
    [Security.SecureString]$AiServiceToken,
    [string]$ReportFontPath = 'C:\Windows\Fonts\simhei.ttf',
    [ValidateSet('fake', 'kimi')]
    [string]$AiProvider = 'fake',
    [string]$KimiBaseUrl = 'https://api.moonshot.cn/v1',
    [string]$KimiModel,
    [Security.SecureString]$KimiApiKey,
    [switch]$Restart,
    [switch]$Apply
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$root = Resolve-SafeAbsolutePath -Path $DeploymentRoot -Label 'DeploymentRoot'
$variableNames = @()
if ($ServiceId -in @('three-fees-api', 'three-fees-worker')) {
    $variableNames += @(
        'DB_URL',
        'DB_USERNAME',
        'DB_PASSWORD',
        'APP_FILE_ROOT',
        'REPORT_FONT_PATH',
        'AI_SERVICE_BASE_URL',
        'AI_SERVICE_TOKEN',
        'INITIAL_ACCOUNT_BOOTSTRAP_ENABLED'
    )
    if ($ServiceId -eq 'three-fees-api') {
        $variableNames += 'SESSION_COOKIE_SECURE'
        if ($InitialAccountBootstrapEnabled) {
            $variableNames += 'INITIAL_ACCOUNT_PASSWORD'
        }
    }
}
else {
    $variableNames += @(
        'AI_SERVICE_TOKEN',
        'AI_MODEL_PROVIDER',
        'KIMI_BASE_URL',
        'KIMI_MODEL'
    )
    if ($AiProvider -eq 'kimi') {
        $variableNames += 'KIMI_API_KEY'
    }
}

if ($ServiceId -in @('three-fees-api', 'three-fees-worker')) {
    if ($DatabaseUrl -notmatch '^jdbc:mysql://') {
        throw 'DB_URL must use the jdbc:mysql:// scheme.'
    }
    if ($DatabaseUrl -match '(?i)jdbc:mysql://[^:/\s]+:[^@\s]+@' -or $DatabaseUrl -match '(?i)(?:password|user)=[^&]+') {
        throw 'DB_URL must not embed database credentials; use DB_USERNAME and DB_PASSWORD.'
    }
    $resolvedReportFont = Resolve-ReadableReportFont -ReportFontPath $ReportFontPath
}

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

if ($null -eq $AiServiceToken) {
    $AiServiceToken = Read-Host 'Enter the shared Java/Python AI service token' -AsSecureString
}

$environment = @()
$plainDatabasePassword = $null
$plainAiServiceToken = $null
$plainKimiApiKey = $null
$plainInitialAccountPassword = $null
try {
    $plainAiServiceToken = Convert-SecureStringToPlainText -SecureValue $AiServiceToken
    if ([string]::IsNullOrWhiteSpace($plainAiServiceToken)) {
        throw 'AI service token cannot be empty.'
    }

    if ($ServiceId -in @('three-fees-api', 'three-fees-worker')) {
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
            'AI_SERVICE_BASE_URL=http://127.0.0.1:8100',
            "AI_SERVICE_TOKEN=$plainAiServiceToken"
        )
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
    }
    else {
        $environment += @(
            "AI_SERVICE_TOKEN=$plainAiServiceToken",
            "AI_MODEL_PROVIDER=$AiProvider",
            "KIMI_BASE_URL=$KimiBaseUrl",
            "KIMI_MODEL=$KimiModel"
        )
        if ($AiProvider -eq 'kimi') {
            if ($null -eq $KimiApiKey) {
                $KimiApiKey = Read-Host 'Enter the rotated Kimi API key' -AsSecureString
            }
            $plainKimiApiKey = Convert-SecureStringToPlainText -SecureValue $KimiApiKey
            if ([string]::IsNullOrWhiteSpace($plainKimiApiKey)) {
                throw 'Kimi API key cannot be empty when AI_MODEL_PROVIDER=kimi.'
            }
            $environment += "KIMI_API_KEY=$plainKimiApiKey"
        }
    }

    $registryPath = "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceId"
    New-ItemProperty -LiteralPath $registryPath -Name 'Environment' -PropertyType MultiString -Value $environment -Force | Out-Null
}
finally {
    $plainDatabasePassword = $null
    $plainAiServiceToken = $null
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
