[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function Test-EnvPresent {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,
        [Parameter(Mandatory = $true)]
        [string] $Target
    )

    $value = [Environment]::GetEnvironmentVariable($Name, $Target)
    return -not [string]::IsNullOrWhiteSpace($value)
}

function Get-EnvValue {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,
        [string] $Default = ''
    )

    foreach ($target in @('Process', 'User', 'Machine')) {
        $value = [Environment]::GetEnvironmentVariable($Name, $target)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value
        }
    }

    return $Default
}

$apiKeyPresentInProcess = Test-EnvPresent -Name 'KIMI_API_KEY' -Target 'Process'
$apiKeyPresentInUser = Test-EnvPresent -Name 'KIMI_API_KEY' -Target 'User'
$apiKeyPresentInMachine = Test-EnvPresent -Name 'KIMI_API_KEY' -Target 'Machine'
$apiKey = Get-EnvValue -Name 'KIMI_API_KEY'
$aiEnabled = Get-EnvValue -Name 'AI_ENABLED' -Default ''
$baseUrl = Get-EnvValue -Name 'KIMI_BASE_URL' -Default 'https://api.moonshot.cn/v1'
$model = Get-EnvValue -Name 'KIMI_MODEL' -Default 'kimi-k3'

Write-Host 'Kimi 本地环境检查'
Write-Host ('- 当前终端 KIMI_API_KEY：' + $(if ($apiKeyPresentInProcess) { '已配置' } else { '未配置' }))
Write-Host ('- 用户环境 KIMI_API_KEY：' + $(if ($apiKeyPresentInUser) { '已配置' } else { '未配置' }))
Write-Host ('- 系统环境 KIMI_API_KEY：' + $(if ($apiKeyPresentInMachine) { '已配置' } else { '未配置' }))
Write-Host ('- AI_ENABLED：' + $(if ([string]::IsNullOrWhiteSpace($aiEnabled)) { '未设置，启动脚本会注入 true' } else { $aiEnabled }))
Write-Host ('- KIMI_BASE_URL：' + $baseUrl)
Write-Host ('- KIMI_MODEL：' + $model)

if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Write-Error 'Kimi 密钥未配置，请先设置 KIMI_API_KEY 后重新启动后端。'
}

Write-Host 'Kimi 密钥已检测到，检查通过。'

