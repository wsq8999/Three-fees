[CmdletBinding()]
param(
    [switch] $SkipPortCheck
)

$ErrorActionPreference = 'Stop'

$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$backendRoot = Join-Path $root 'backend'
$frontendRoot = Join-Path $root 'frontend'

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

function Test-PortAvailable {
    param(
        [Parameter(Mandatory = $true)]
        [int] $Port,
        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($null -eq $connections) {
        return $true
    }

    Write-Host ("端口 {0} 已被占用，{1} 无法启动。" -f $Port, $Name) -ForegroundColor Red
    foreach ($connection in $connections) {
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        $processName = if ($null -eq $process) { '未知进程' } else { $process.ProcessName }
        Write-Host ("- PID {0}：{1}" -f $connection.OwningProcess, $processName)
    }
    return $false
}

$apiKey = Get-EnvValue -Name 'KIMI_API_KEY'
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Write-Error 'Kimi 密钥未配置，请先设置 KIMI_API_KEY 后重新启动后端。'
}

$baseUrl = Get-EnvValue -Name 'KIMI_BASE_URL' -Default 'https://api.moonshot.cn/v1'
$model = Get-EnvValue -Name 'KIMI_MODEL' -Default 'kimi-k3'
$dbUrl = Get-EnvValue -Name 'DB_URL' -Default 'jdbc:mysql://127.0.0.1:3306/three_fees?characterEncoding=utf8&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true'
$dbUsername = Get-EnvValue -Name 'DB_USERNAME' -Default 'root'
$dbPassword = Get-EnvValue -Name 'DB_PASSWORD' -Default '547547'
$appFileRoot = Get-EnvValue -Name 'APP_FILE_ROOT' -Default (Join-Path $backendRoot 'runtime/files')

if (-not $SkipPortCheck) {
    $backendAvailable = Test-PortAvailable -Port 8080 -Name '后端'
    $frontendAvailable = Test-PortAvailable -Port 5173 -Name '前端'
    if (-not ($backendAvailable -and $frontendAvailable)) {
        Write-Error '请先关闭占用端口的旧进程，再重新执行启动脚本。'
    }
}

[Environment]::SetEnvironmentVariable('SPRING_PROFILES_ACTIVE', 'dev', 'Process')
[Environment]::SetEnvironmentVariable('THREE_FEES_PROCESS_ROLE', 'all', 'Process')
[Environment]::SetEnvironmentVariable('AI_ENABLED', 'true', 'Process')
[Environment]::SetEnvironmentVariable('KIMI_API_KEY', $apiKey, 'Process')
[Environment]::SetEnvironmentVariable('KIMI_BASE_URL', $baseUrl, 'Process')
[Environment]::SetEnvironmentVariable('KIMI_MODEL', $model, 'Process')
[Environment]::SetEnvironmentVariable('DB_URL', $dbUrl, 'Process')
[Environment]::SetEnvironmentVariable('DB_USERNAME', $dbUsername, 'Process')
[Environment]::SetEnvironmentVariable('DB_PASSWORD', $dbPassword, 'Process')
[Environment]::SetEnvironmentVariable('APP_FILE_ROOT', $appFileRoot, 'Process')
[Environment]::SetEnvironmentVariable('INITIAL_ACCOUNT_BOOTSTRAP_ENABLED', (Get-EnvValue -Name 'INITIAL_ACCOUNT_BOOTSTRAP_ENABLED' -Default 'true'), 'Process')
[Environment]::SetEnvironmentVariable('INITIAL_ACCOUNT_PASSWORD', (Get-EnvValue -Name 'INITIAL_ACCOUNT_PASSWORD' -Default '123456'), 'Process')
[Environment]::SetEnvironmentVariable('VITE_API_PROXY_TARGET', 'http://127.0.0.1:8080', 'Process')

Write-Host '已检测到 Kimi 密钥，已为后端启动进程注入 AI_ENABLED=true。'
Write-Host ('Kimi 接口：' + $baseUrl)
Write-Host ('Kimi 模型：' + $model)
Write-Host '正在启动后端和前端，请在新打开的两个窗口查看运行日志。'

Start-Process powershell.exe `
    -WorkingDirectory $backendRoot `
    -WindowStyle Normal `
    -ArgumentList @('-NoExit', '-Command', '.\mvnw.cmd spring-boot:run')

Start-Process powershell.exe `
    -WorkingDirectory $frontendRoot `
    -WindowStyle Normal `
    -ArgumentList @('-NoExit', '-Command', 'corepack pnpm dev --host 127.0.0.1 --port 5173')

Write-Host '启动命令已发出。后端地址：http://127.0.0.1:8080，前端地址：http://127.0.0.1:5173。'

