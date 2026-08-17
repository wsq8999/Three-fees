#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$OutputDirectory,
    [string]$Version = (Get-Date -Format 'yyyyMMdd.HHmmss'),
    [string]$PnpmExe = 'corepack.cmd',
    [switch]$SkipBuild
)

. (Join-Path $PSScriptRoot 'Common.ps1')

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $RepositoryRoot 'artifacts'
}

function Copy-DirectoryContent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Source,

        [Parameter(Mandatory = $true)]
        [string]$Destination,

        [string[]]$ExcludedPatterns = @()
    )

    $sourceRoot = (Resolve-Path -LiteralPath $Source).Path.TrimEnd('\')
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -File -Recurse) {
        $relativePath = $file.FullName.Substring($sourceRoot.Length).TrimStart('\')
        $excluded = $false
        foreach ($pattern in $ExcludedPatterns) {
            if ($relativePath -match $pattern) {
                $excluded = $true
                break
            }
        }
        if ($excluded) {
            continue
        }

        $targetPath = Join-Path $Destination $relativePath
        $targetDirectory = Split-Path -Parent $targetPath
        New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $targetPath
    }
}

function Assert-NoLikelySecrets {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root
    )

    $textExtensions = @('.config', '.css', '.env', '.html', '.ini', '.js', '.json', '.map', '.md', '.mjs', '.properties', '.ps1', '.psd1', '.py', '.toml', '.ts', '.txt', '.xml', '.yaml', '.yml')
    $quotedAssignmentPattern = '(?i)(api[_-]?key|client[_-]?secret|password|private[_-]?key|token)\s*[:=]\s*["''][^"''\r\n]{16,}["'']'
    $environmentAssignmentPattern = '(?im)^\s*[A-Z][A-Z0-9_]*(?:API_KEY|CLIENT_SECRET|PASSWORD|PRIVATE_KEY|TOKEN)\s*=\s*[A-Za-z0-9_\-./+=]{16,}\s*(?:#.*)?$'
    $credentialUrlPattern = '(?i)(mysql|postgres(?:ql)?)://[A-Za-z0-9._%+\-]+:[A-Za-z0-9._~!$&()*+,;=%+\-]+@'
    $providerKeyPattern = '(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}'
    $privateKeyPattern = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
    foreach ($file in Get-ChildItem -LiteralPath $Root -File -Recurse) {
        if ($textExtensions -notcontains $file.Extension.ToLowerInvariant()) {
            continue
        }
        $content = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
        if ($content -match $quotedAssignmentPattern -or
            $content -match $environmentAssignmentPattern -or
            $content -match $credentialUrlPattern -or
            $content -match $providerKeyPattern -or
            $content -match $privateKeyPattern) {
            throw "Potential secret detected in release file: $($file.FullName)"
        }
    }
}

function Assert-NoForbiddenReleaseContent {
    param([Parameter(Mandatory = $true)][string]$Root)

    $forbiddenSegments = @(
        'node_modules', '.venv', 'venv', '__pycache__', '.pytest_cache', '.ruff_cache',
        '.mypy_cache', 'playwright-report', 'test-results', 'coverage', 'uploads', 'backups',
        'runtime-data', 'test-data'
    )
    foreach ($file in Get-ChildItem -LiteralPath $Root -File -Recurse) {
        $relativePath = $file.FullName.Substring($Root.Length).TrimStart('\').Replace('\', '/')
        $segments = $relativePath.Split('/')
        if (@($segments | Where-Object { $_ -in $forbiddenSegments }).Count -gt 0 -or
            $file.Extension.ToLowerInvariant() -in @('.log', '.tmp') -or
            $file.Name -match '^(?i:test-.+\.ps1|\.env(?:\..+)?)$') {
            throw "Forbidden runtime/test content detected in release: $relativePath"
        }
    }
}

function Resolve-BuildExecutable {
    param(
        [Parameter(Mandatory = $true)][string]$Candidate,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ([System.IO.Path]::IsPathRooted($Candidate)) {
        $path = [System.IO.Path]::GetFullPath($Candidate)
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "$Label does not exist: $path"
        }
        return $path
    }
    $command = Get-Command $Candidate -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "$Label is unavailable: $Candidate"
    }
    return $command.Source
}

function Invoke-ReleasePnpm {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )

    $actualArguments = @($Arguments)
    if ([System.IO.Path]::GetFileNameWithoutExtension($Executable) -ieq 'corepack') {
        $actualArguments = @('pnpm') + $actualArguments
    }
    Invoke-CheckedProcess -FilePath $Executable -ArgumentList $actualArguments -WorkingDirectory $WorkingDirectory
}

$repository = Resolve-SafeAbsolutePath -Path $RepositoryRoot -Label 'RepositoryRoot'
$output = Resolve-SafeAbsolutePath -Path $OutputDirectory -Label 'OutputDirectory'
Assert-SafeVersion -Version $Version

$backendRoot = Join-Path $repository 'backend'
$frontendRoot = Join-Path $repository 'frontend'
$deployRoot = Join-Path $repository 'deploy\windows'
foreach ($requiredDirectory in @($backendRoot, $frontendRoot, $deployRoot)) {
    if (-not (Test-Path -LiteralPath $requiredDirectory -PathType Container)) {
        throw "Required project directory is missing: $requiredDirectory"
    }
}

if (-not $SkipBuild) {
    $mavenWrapper = Join-Path $backendRoot 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
        throw "Maven Wrapper is required: $mavenWrapper"
    }
    Invoke-CheckedProcess -FilePath $mavenWrapper -ArgumentList @('-B', 'clean', 'verify') -WorkingDirectory $backendRoot

    $resolvedPnpm = Resolve-BuildExecutable -Candidate $PnpmExe -Label 'pnpm/Corepack executable'
    Invoke-ReleasePnpm -Executable $resolvedPnpm -Arguments @('install', '--frozen-lockfile') -WorkingDirectory $frontendRoot
    Invoke-ReleasePnpm -Executable $resolvedPnpm -Arguments @('run', 'build') -WorkingDirectory $frontendRoot
}

$jarCandidates = @(
    Get-ChildItem -LiteralPath (Join-Path $backendRoot 'target') -Filter '*.jar' -File |
        Where-Object { $_.Name -notmatch '(^original-|-(sources|javadoc|tests)\.jar$)' }
)
if ($jarCandidates.Count -ne 1) {
    throw "Expected exactly one runnable backend JAR, found $($jarCandidates.Count)."
}

$frontendDist = Join-Path $frontendRoot 'dist'
if (-not (Test-Path -LiteralPath (Join-Path $frontendDist 'index.html') -PathType Leaf)) {
    throw "Frontend build output is missing: $frontendDist\index.html"
}

New-Item -ItemType Directory -Path $output -Force | Out-Null
$stagingRoot = Join-Path $output ('.staging-' + [Guid]::NewGuid().ToString('N'))
$archivePath = Join-Path $output ("three-fees-$Version.zip")
Assert-PathInside -ParentPath $output -ChildPath $stagingRoot -Label 'Staging path'
if (Test-Path -LiteralPath $archivePath) {
    throw "Release archive already exists: $archivePath"
}

try {
    New-Item -ItemType Directory -Path $stagingRoot | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $stagingRoot 'backend') | Out-Null
    Copy-Item -LiteralPath $jarCandidates[0].FullName -Destination (Join-Path $stagingRoot 'backend\three-fees-api.jar')

    Copy-DirectoryContent -Source $frontendDist -Destination (Join-Path $stagingRoot 'frontend') -ExcludedPatterns @('\.map$')
    Copy-Item -LiteralPath (Join-Path $deployRoot 'config\iis\web.config') -Destination (Join-Path $stagingRoot 'frontend\web.config')

    $deploymentExclusions = @(
        '(^|\\)Test-[^\\]+\.ps1$'
    )
    Copy-DirectoryContent `
        -Source $deployRoot `
        -Destination (Join-Path $stagingRoot 'deployment\windows') `
        -ExcludedPatterns $deploymentExclusions

    $deploymentDocs = Join-Path $repository 'docs\deployment'
    if (Test-Path -LiteralPath $deploymentDocs -PathType Container) {
        Copy-DirectoryContent -Source $deploymentDocs -Destination (Join-Path $stagingRoot 'deployment\docs')
    }

    Assert-NoLikelySecrets -Root $stagingRoot
    Assert-NoForbiddenReleaseContent -Root $stagingRoot

    $gitCommit = 'unknown'
    try {
        $commit = & git -C $repository rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $commit) {
            $gitCommit = ([string]$commit).Trim()
        }
    }
    catch {
        $gitCommit = 'unknown'
    }

    $manifestFiles = @()
    foreach ($file in Get-ChildItem -LiteralPath $stagingRoot -File -Recurse | Sort-Object FullName) {
        $relativePath = $file.FullName.Substring($stagingRoot.Length).TrimStart('\').Replace('\', '/')
        $manifestFiles += [ordered]@{
            path   = $relativePath
            length = [long]$file.Length
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }

    $manifest = [ordered]@{
        schemaVersion = 1
        version       = $Version
        createdAtUtc  = [DateTime]::UtcNow.ToString('o')
        gitCommit     = $gitCommit
        files         = $manifestFiles
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $stagingRoot 'manifest.json') -Encoding UTF8

    Test-ReleaseDirectory -ReleaseRoot $stagingRoot | Out-Null
    Compress-Archive -Path (Join-Path $stagingRoot '*') -DestinationPath $archivePath -CompressionLevel Optimal
    Test-ReleaseArchive -ArchivePath $archivePath | Out-Null

    $archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    [pscustomobject]@{
        Version      = $Version
        Archive      = $archivePath
        Sha256       = $archiveHash
        GitCommit    = $gitCommit
    }
}
finally {
    if (Test-Path -LiteralPath $stagingRoot) {
        Assert-PathInside -ParentPath $output -ChildPath $stagingRoot -Label 'Staging cleanup path'
        if ((Split-Path -Leaf $stagingRoot) -notlike '.staging-*') {
            throw "Refusing to clean unexpected staging path: $stagingRoot"
        }
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
}
