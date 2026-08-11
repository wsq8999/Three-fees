#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$BaselineRoot,
    [string]$ReleaseRoot
)

. (Join-Path $PSScriptRoot 'Common.ps1')

if ([string]::IsNullOrWhiteSpace($BaselineRoot)) {
    $BaselineRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

$root = Resolve-SafeAbsolutePath -Path $BaselineRoot -Label 'BaselineRoot'
$failures = New-Object System.Collections.Generic.List[string]
$passes = New-Object System.Collections.Generic.List[string]

function Add-CheckResult {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Passed,
        [string]$Detail
    )

    if ($Passed) {
        $script:passes.Add($Name)
    }
    else {
        $script:failures.Add($(if ($Detail) { "$Name`: $Detail" } else { $Name }))
    }
}

$requiredFiles = @(
    'README.md',
    'config\deployment-layout.json',
    'config\environment.example.psd1',
    'config\iis\web.config',
    'config\winsw\three-fees-api.xml',
    'config\winsw\three-fees-worker.xml',
    'config\winsw\three-fees-ai.xml',
    'scripts\Build-Release.ps1',
    'scripts\Install-ThreeFees.ps1',
    'scripts\Set-ServiceEnvironment.ps1',
    'scripts\Upgrade-ThreeFees.ps1',
    'scripts\Rollback-ThreeFees.ps1',
    'scripts\Test-DeploymentScripts.ps1',
    'scripts\Common.ps1'
)
foreach ($requiredFile in $requiredFiles) {
    Add-CheckResult -Name "required:$requiredFile" -Passed (Test-Path -LiteralPath (Join-Path $root $requiredFile) -PathType Leaf)
}

foreach ($scriptFile in Get-ChildItem -LiteralPath (Join-Path $root 'scripts') -Filter '*.ps1' -File) {
    $tokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($scriptFile.FullName, [ref]$tokens, [ref]$parseErrors) | Out-Null
    Add-CheckResult -Name "powershell-ast:$($scriptFile.Name)" -Passed (@($parseErrors).Count -eq 0) -Detail ($parseErrors -join '; ')
}

foreach ($xmlFile in Get-ChildItem -LiteralPath (Join-Path $root 'config') -File -Recurse | Where-Object { $_.Extension -in @('.xml', '.config') }) {
    try {
        [xml](Get-Content -LiteralPath $xmlFile.FullName -Raw -Encoding UTF8) | Out-Null
        Add-CheckResult -Name "xml:$($xmlFile.Name)" -Passed $true
    }
    catch {
        Add-CheckResult -Name "xml:$($xmlFile.Name)" -Passed $false -Detail $_.Exception.Message
    }
}

foreach ($jsonFile in Get-ChildItem -LiteralPath (Join-Path $root 'config') -Filter '*.json' -File -Recurse) {
    try {
        Get-Content -LiteralPath $jsonFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json | Out-Null
        Add-CheckResult -Name "json:$($jsonFile.Name)" -Passed $true
    }
    catch {
        Add-CheckResult -Name "json:$($jsonFile.Name)" -Passed $false -Detail $_.Exception.Message
    }
}

try {
    $environmentExample = Import-PowerShellDataFile -LiteralPath (Join-Path $root 'config\environment.example.psd1')
    Add-CheckResult -Name 'environment-example:parse' -Passed $true
    foreach ($secretName in @('DB_PASSWORD', 'AI_SERVICE_TOKEN', 'KIMI_API_KEY', 'INITIAL_ACCOUNT_PASSWORD')) {
        $values = @()
        foreach ($section in $environmentExample.Values) {
            if ($section -is [hashtable] -and $section.ContainsKey($secretName)) {
                $values += [string]$section[$secretName]
            }
        }
        Add-CheckResult -Name "environment-example:$secretName-empty" -Passed (@($values | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -eq 0)
    }
}
catch {
    Add-CheckResult -Name 'environment-example:parse' -Passed $false -Detail $_.Exception.Message
}

$expectedServiceIds = @('three-fees-api', 'three-fees-worker', 'three-fees-ai')
$actualServiceIds = @()
foreach ($serviceId in $expectedServiceIds) {
    $xmlPath = Join-Path $root ("config\winsw\$serviceId.xml")
    try {
        [xml]$serviceXml = Get-Content -LiteralPath $xmlPath -Raw -Encoding UTF8
        $actualServiceIds += [string]$serviceXml.service.id
        Add-CheckResult -Name "winsw:${serviceId}:id" -Passed ([string]$serviceXml.service.id -ceq $serviceId)
        Add-CheckResult -Name "winsw:${serviceId}:root-placeholder" -Passed (([string]$serviceXml.OuterXml).Contains('__DEPLOYMENT_ROOT__'))
        Add-CheckResult -Name "winsw:${serviceId}:network-service-v2" -Passed (
            [string]$serviceXml.service.serviceaccount.domain -eq 'NT AUTHORITY' -and
            [string]$serviceXml.service.serviceaccount.user -eq 'NetworkService' -and
            $null -eq $serviceXml.service.serviceaccount.SelectSingleNode('username')
        )
    }
    catch {
        Add-CheckResult -Name "winsw:${serviceId}:load" -Passed $false -Detail $_.Exception.Message
    }
}
$serviceIdDifferences = @(Compare-Object $expectedServiceIds $actualServiceIds)
Add-CheckResult -Name 'winsw:service-id-set' -Passed ($serviceIdDifferences.Count -eq 0)

[xml]$apiXml = Get-Content -LiteralPath (Join-Path $root 'config\winsw\three-fees-api.xml') -Raw -Encoding UTF8
[xml]$aiXml = Get-Content -LiteralPath (Join-Path $root 'config\winsw\three-fees-ai.xml') -Raw -Encoding UTF8
[xml]$workerXml = Get-Content -LiteralPath (Join-Path $root 'config\winsw\three-fees-worker.xml') -Raw -Encoding UTF8
$apiXmlText = $apiXml.OuterXml
$aiXmlText = $aiXml.OuterXml
Add-CheckResult -Name 'api:loopback-8080' -Passed ($apiXmlText.Contains('127.0.0.1') -and $apiXmlText.Contains('8080'))
Add-CheckResult -Name 'ai:loopback-8100' -Passed ($aiXmlText.Contains('--host 127.0.0.1') -and $aiXmlText.Contains('--port 8100'))
Add-CheckResult -Name 'ai:not-wildcard-bound' -Passed (-not $aiXmlText.Contains('0.0.0.0'))
Add-CheckResult -Name 'winsw:v2-no-hidewindow' -Passed (-not ($apiXmlText.Contains('hidewindow') -or $aiXmlText.Contains('hidewindow')))
Add-CheckResult -Name 'worker:automatic-durable-consumer' -Passed (
    [string]$workerXml.service.startmode -eq 'Automatic' -and
    $null -ne $workerXml.service.delayedAutoStart
)

$layout = Get-Content -LiteralPath (Join-Path $root 'config\deployment-layout.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$layoutWorker = @($layout.services | Where-Object { $_.id -eq 'three-fees-worker' })
Add-CheckResult -Name 'layout:schema-v2' -Passed ($layout.schemaVersion -eq 2)
Add-CheckResult -Name 'layout:worker-automatic' -Passed ($layoutWorker.Count -eq 1 -and $layoutWorker[0].startMode -eq 'Automatic')

[xml]$iisXml = Get-Content -LiteralPath (Join-Path $root 'config\iis\web.config') -Raw -Encoding UTF8
$iisText = $iisXml.OuterXml
Add-CheckResult -Name 'iis:api-loopback-proxy' -Passed ($iisText.Contains('http://127.0.0.1:8080/api/'))
Add-CheckResult -Name 'iis:health-loopback-proxy' -Passed ($iisText.Contains('http://127.0.0.1:8080/actuator/health'))
Add-CheckResult -Name 'iis:no-ai-port-proxy' -Passed (-not $iisText.Contains(':8100'))
Add-CheckResult -Name 'iis:internal-block-rule' -Passed ($iisText.Contains('Block internal AI routes'))
Add-CheckResult -Name 'iis:upload-limit-100mb' -Passed ($iisText.Contains('maxAllowedContentLength="104857600"'))

foreach ($mutatingScript in @('Install-ThreeFees.ps1', 'Set-ServiceEnvironment.ps1', 'Upgrade-ThreeFees.ps1', 'Rollback-ThreeFees.ps1')) {
    $scriptText = Get-Content -LiteralPath (Join-Path $root ("scripts\$mutatingScript")) -Raw -Encoding UTF8
    Add-CheckResult -Name "apply-gate:$mutatingScript" -Passed ($scriptText -match '\[switch\]\$Apply' -and $scriptText -match 'if \(-not \$Apply\)')
}

$buildScriptText = Get-Content -LiteralPath (Join-Path $root 'scripts\Build-Release.ps1') -Raw -Encoding UTF8
Add-CheckResult -Name 'build:explicit-pnpm-exe' -Passed ($buildScriptText.Contains('[string]$PnpmExe'))
Add-CheckResult -Name 'build:explicit-python-exe' -Passed ($buildScriptText.Contains('[string]$PythonExe'))
Add-CheckResult -Name 'build:forbidden-runtime-content-gate' -Passed ($buildScriptText.Contains('Assert-NoForbiddenReleaseContent'))

$environmentScriptText = Get-Content -LiteralPath (Join-Path $root 'scripts\Set-ServiceEnvironment.ps1') -Raw -Encoding UTF8
Add-CheckResult -Name 'environment:report-font-contract' -Passed ($environmentScriptText.Contains('REPORT_FONT_PATH') -and $environmentScriptText.Contains('Resolve-ReadableReportFont'))
Add-CheckResult -Name 'environment:ai-provider-contract' -Passed ($environmentScriptText.Contains('AI_MODEL_PROVIDER'))

$configTextFiles = Get-ChildItem -LiteralPath (Join-Path $root 'config') -File -Recurse
$secretAssignmentPattern = '(?i)(api[_-]?key|client[_-]?secret|password|private[_-]?key|token)\s*[:=]\s*["'']?[A-Za-z0-9_\-./+=]{16,}'
$credentialUrlPattern = '(?i)(mysql|postgres(?:ql)?)://[^:/\s]+:[^@\s]+@'
$providerKeyPattern = '(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}'
foreach ($configFile in $configTextFiles) {
    $content = Get-Content -LiteralPath $configFile.FullName -Raw -Encoding UTF8
    Add-CheckResult -Name "secret-scan:$($configFile.Name)" -Passed (-not ($content -match $secretAssignmentPattern -or $content -match $credentialUrlPattern -or $content -match $providerKeyPattern))
}

if ($ReleaseRoot) {
    try {
        Test-ReleaseDirectory -ReleaseRoot $ReleaseRoot | Out-Null
        Add-CheckResult -Name 'release-directory:manifest-and-hashes' -Passed $true
    }
    catch {
        Add-CheckResult -Name 'release-directory:manifest-and-hashes' -Passed $false -Detail $_.Exception.Message
    }
}

foreach ($pass in $passes) {
    Write-Host "PASS $pass"
}
if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Error "FAIL $failure"
    }
    throw "Deployment baseline verification failed with $($failures.Count) issue(s)."
}

Write-Host "Deployment baseline verification passed: $($passes.Count) checks."
