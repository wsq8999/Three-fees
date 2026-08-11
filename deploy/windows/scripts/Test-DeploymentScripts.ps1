#requires -Version 5.1
[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'Common.ps1')

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('three-fees-deploy-test-' + [Guid]::NewGuid().ToString('N'))
$testRoot = Resolve-SafeAbsolutePath -Path $testRoot -Label 'TestRoot'
$passes = New-Object System.Collections.Generic.List[string]

function Assert-Test {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if (-not $Condition) {
        throw "Test failed: $Name"
    }
    $script:passes.Add($Name)
}

function Assert-Throws {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $threw = $false
    try {
        & $Action
    }
    catch {
        $threw = $true
    }
    Assert-Test -Condition $threw -Name $Name
}

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $releaseRoot = Join-Path $testRoot 'release'
    foreach ($directory in @(
        (Join-Path $releaseRoot 'backend'),
        (Join-Path $releaseRoot 'frontend'),
        (Join-Path $releaseRoot 'ai-service')
    )) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }

    'fixture jar' | Set-Content -LiteralPath (Join-Path $releaseRoot 'backend\three-fees-api.jar') -Encoding ASCII
    '<!doctype html><title>fixture</title>' | Set-Content -LiteralPath (Join-Path $releaseRoot 'frontend\index.html') -Encoding ASCII
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\config\iis\web.config') -Destination (Join-Path $releaseRoot 'frontend\web.config')
    '# fixture only' | Set-Content -LiteralPath (Join-Path $releaseRoot 'ai-service\requirements.txt') -Encoding ASCII

    $files = @()
    foreach ($file in Get-ChildItem -LiteralPath $releaseRoot -File -Recurse | Sort-Object FullName) {
        $files += [ordered]@{
            path   = $file.FullName.Substring($releaseRoot.Length).TrimStart('\').Replace('\', '/')
            length = [long]$file.Length
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $manifest = [ordered]@{
        schemaVersion = 1
        version       = 'test.1'
        createdAtUtc  = [DateTime]::UtcNow.ToString('o')
        gitCommit     = 'fixture'
        files         = $files
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $releaseRoot 'manifest.json') -Encoding UTF8

    $validatedManifest = Test-ReleaseDirectory -ReleaseRoot $releaseRoot
    Assert-Test -Condition ([string]$validatedManifest.version -eq 'test.1') -Name 'valid release directory and SHA-256 manifest'

    $archivePath = Join-Path $testRoot 'release.zip'
    Compress-Archive -Path (Join-Path $releaseRoot '*') -DestinationPath $archivePath
    $archiveManifest = Test-ReleaseArchive -ArchivePath $archivePath
    Assert-Test -Condition ([string]$archiveManifest.version -eq 'test.1') -Name 'valid release ZIP preflight'

    Add-Content -LiteralPath (Join-Path $releaseRoot 'frontend\index.html') -Value 'tampered' -Encoding ASCII
    Assert-Throws -Name 'tampered file is rejected by SHA-256 validation' -Action {
        Test-ReleaseDirectory -ReleaseRoot $releaseRoot | Out-Null
    }

    Assert-Throws -Name 'unsafe release version is rejected' -Action {
        Assert-SafeVersion -Version '..\escape'
    }
    Assert-Throws -Name 'drive root is rejected' -Action {
        Resolve-SafeAbsolutePath -Path ([System.IO.Path]::GetPathRoot($testRoot)) -Label 'UnsafeRoot' | Out-Null
    }
    Assert-Throws -Name 'path outside parent is rejected' -Action {
        Assert-PathInside -ParentPath $testRoot -ChildPath (Split-Path -Parent $testRoot) -Label 'EscapedPath'
    }
    Assert-Throws -Name 'JDBC URL with embedded credentials is rejected during preflight' -Action {
        & (Join-Path $PSScriptRoot 'Set-ServiceEnvironment.ps1') `
            -ServiceId 'three-fees-api' `
            -DatabaseUrl 'jdbc:mysql://user:secret@127.0.0.1:3306/three_fees' | Out-Null
    }
    Assert-Throws -Name 'relative report font path is rejected' -Action {
        Resolve-ReadableReportFont -ReportFontPath '.\font.ttf' | Out-Null
    }
    $windowsFont = @(
        'C:\Windows\Fonts\simhei.ttf'
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if ($windowsFont) {
        $resolvedFont = Resolve-ReadableReportFont -ReportFontPath $windowsFont
        Assert-Test -Condition ($resolvedFont -eq $windowsFont) -Name 'absolute readable Chinese report font passes preflight'
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $maliciousArchivePath = Join-Path $testRoot 'malicious.zip'
    $fileStream = New-Object System.IO.FileStream($maliciousArchivePath, [System.IO.FileMode]::CreateNew)
    try {
        $zip = New-Object System.IO.Compression.ZipArchive($fileStream, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry = $zip.CreateEntry('../escape.txt')
            $writer = New-Object System.IO.StreamWriter($entry.Open())
            try {
                $writer.Write('blocked')
            }
            finally {
                $writer.Dispose()
            }
        }
        finally {
            $zip.Dispose()
        }
    }
    finally {
        $fileStream.Dispose()
    }
    Assert-Throws -Name 'ZIP path traversal is rejected' -Action {
        Test-ReleaseArchive -ArchivePath $maliciousArchivePath | Out-Null
    }

    $java = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    $python = Get-Command 'python.exe' -ErrorAction SilentlyContinue
    if ($null -eq $python) {
        $repositoryPython = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\ai-service\.venv\Scripts\python.exe'))
        if (Test-Path -LiteralPath $repositoryPython -PathType Leaf) {
            $python = [pscustomobject]@{ Source = $repositoryPython }
        }
    }
    if ($null -ne $java -and $null -ne $python) {
        $fakeWrapper = Join-Path $testRoot 'winsw-fixture.exe'
        'fixture wrapper' | Set-Content -LiteralPath $fakeWrapper -Encoding ASCII
        $wrapperHash = (Get-FileHash -LiteralPath $fakeWrapper -Algorithm SHA256).Hash
        $dryRunTarget = Join-Path $testRoot 'dry-run-target'
        & (Join-Path $PSScriptRoot 'Install-ThreeFees.ps1') `
            -ReleaseArchive $archivePath `
            -WinSWExecutable $fakeWrapper `
            -WinSWExpectedSha256 $wrapperHash `
            -DeploymentRoot $dryRunTarget `
            -JavaExe $java.Source `
            -PythonExe $python.Source `
            -AllowHttp | Out-Null
        Assert-Test -Condition (-not (Test-Path -LiteralPath $dryRunTarget)) -Name 'install preflight performs no filesystem mutation'
    }
    else {
        Write-Warning 'Java or Python was unavailable; install preflight no-mutation test was skipped.'
    }

    foreach ($pass in $passes) {
        Write-Host "PASS $pass"
    }
    Write-Host "Deployment script behavior tests passed: $($passes.Count) checks."
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        $leaf = Split-Path -Leaf $testRoot
        $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
        Assert-PathInside -ParentPath $tempRoot -ChildPath $testRoot -Label 'Test cleanup path'
        if ($leaf -notlike 'three-fees-deploy-test-*') {
            throw "Refusing to remove unexpected test path: $testRoot"
        }
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
