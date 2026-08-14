$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "547547"
$env:APP_FILE_ROOT = "D:\Three-fees\.runtime\files"
$env:SESSION_COOKIE_SECURE = "false"
Set-Location "D:\Three-fees\backend"
mvn org.springframework.boot:spring-boot-maven-plugin:run
