$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "547547"
$env:APP_FILE_ROOT = "D:\Three-fees\.runtime\files"
$env:SESSION_COOKIE_SECURE = "false"
$env:AI_ENABLED = "true"
$env:KIMI_API_KEY = [Environment]::GetEnvironmentVariable("KIMI_API_KEY", [EnvironmentVariableTarget]::User)
$env:KIMI_BASE_URL = if ($env:KIMI_BASE_URL) { $env:KIMI_BASE_URL } else { "https://api.moonshot.cn/v1" }
$env:KIMI_MODEL = if ($env:KIMI_MODEL) { $env:KIMI_MODEL } else { "kimi-k3" }
$env:TEMP = "D:\Three-fees\.runtime\tmp"
$env:TMP = "D:\Three-fees\.runtime\tmp"
$env:MAVEN_OPTS = "-Dmaven.repo.local=D:\Three-fees\.runtime\m2"
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
Set-Location "D:\Three-fees\backend"
mvn org.springframework.boot:spring-boot-maven-plugin:run
