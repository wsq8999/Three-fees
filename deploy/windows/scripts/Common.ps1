Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ThreeFeesServiceIds = @(
    'three-fees-api',
    'three-fees-worker',
    'three-fees-ai'
)

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'This operation requires an elevated PowerShell session.'
    }
}

function Resolve-SafeAbsolutePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not [System.IO.Path]::IsPathRooted($Path)) {
        throw "$Label must be an absolute path: $Path"
    }

    $fullPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
    $rootPath = [System.IO.Path]::GetPathRoot($fullPath).TrimEnd('\')
    if ([string]::IsNullOrWhiteSpace($fullPath) -or $fullPath -eq $rootPath) {
        throw "$Label cannot be a drive root: $Path"
    }

    return $fullPath
}

function Assert-PathInside {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ParentPath,

        [Parameter(Mandatory = $true)]
        [string]$ChildPath,

        [string]$Label = 'Path'
    )

    $parent = (Resolve-SafeAbsolutePath -Path $ParentPath -Label 'ParentPath') + '\'
    $child = [System.IO.Path]::GetFullPath($ChildPath)
    if (-not $child.StartsWith($parent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label escapes the intended parent directory: $child"
    }
}

function Assert-SafeVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Version
    )

    if ($Version -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$') {
        throw "Release version contains unsafe characters: $Version"
    }
}

function Invoke-CheckedProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$ArgumentList = @(),

        [string]$WorkingDirectory
    )

    $previousLocation = $null
    try {
        if ($WorkingDirectory) {
            $previousLocation = Get-Location
            Set-Location -LiteralPath $WorkingDirectory
        }
        & $FilePath @ArgumentList
        if ($LASTEXITCODE -ne 0) {
            throw "Process failed with exit code $LASTEXITCODE`: $FilePath"
        }
    }
    finally {
        if ($null -ne $previousLocation) {
            Set-Location -LiteralPath $previousLocation
        }
    }
}

function Get-ReleaseManifestFromDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReleaseRoot
    )

    $manifestPath = Join-Path $ReleaseRoot 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Release manifest is missing: $manifestPath"
    }

    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1) {
        throw "Unsupported release manifest schema: $($manifest.schemaVersion)"
    }
    Assert-SafeVersion -Version ([string]$manifest.version)
    if ($null -eq $manifest.files -or $manifest.files.Count -eq 0) {
        throw 'Release manifest contains no files.'
    }

    return $manifest
}

function Test-ReleaseDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReleaseRoot
    )

    $root = Resolve-SafeAbsolutePath -Path $ReleaseRoot -Label 'ReleaseRoot'
    $manifest = Get-ReleaseManifestFromDirectory -ReleaseRoot $root
    $requiredFiles = @(
        'backend\three-fees-api.jar',
        'frontend\index.html',
        'frontend\web.config'
    )

    foreach ($requiredFile in $requiredFiles) {
        $requiredPath = Join-Path $root $requiredFile
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "Required release file is missing: $requiredFile"
        }
    }

    $hasAiDependencyFile = @(
        @(
            'ai-service\requirements.lock',
            'ai-service\requirements.txt',
            'ai-service\pyproject.toml'
        ) | Where-Object { Test-Path -LiteralPath (Join-Path $root $_) -PathType Leaf }
    )
    if ($hasAiDependencyFile.Count -eq 0) {
        throw 'AI service dependency metadata is missing.'
    }

    foreach ($entry in $manifest.files) {
        $relativePath = ([string]$entry.path).Replace('/', '\')
        if ([string]::IsNullOrWhiteSpace($relativePath) -or
            [System.IO.Path]::IsPathRooted($relativePath) -or
            $relativePath -match '(^|\\)\.\.(\\|$)') {
            throw "Unsafe path in release manifest: $relativePath"
        }

        $filePath = [System.IO.Path]::GetFullPath((Join-Path $root $relativePath))
        Assert-PathInside -ParentPath $root -ChildPath $filePath -Label 'Manifest file'
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
            throw "Manifest file is missing: $relativePath"
        }

        $actualLength = (Get-Item -LiteralPath $filePath).Length
        if ([long]$entry.length -ne $actualLength) {
            throw "Manifest length mismatch: $relativePath"
        }

        $actualHash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne ([string]$entry.sha256).ToLowerInvariant()) {
            throw "Manifest SHA-256 mismatch: $relativePath"
        }
    }

    return $manifest
}

function Test-ReleaseArchive {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ArchivePath
    )

    $archiveFullPath = [System.IO.Path]::GetFullPath($ArchivePath)
    if (-not (Test-Path -LiteralPath $archiveFullPath -PathType Leaf)) {
        throw "Release archive does not exist: $archiveFullPath"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($archiveFullPath)
    try {
        if ($zip.Entries.Count -gt 20000) {
            throw 'Release archive has too many entries.'
        }

        $totalLength = [long]0
        $entryNames = @{}
        $manifestEntry = $null
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName.Replace('/', '\')
            if ([string]::IsNullOrWhiteSpace($name)) {
                continue
            }
            if ([System.IO.Path]::IsPathRooted($name) -or
                $name -match '(^|\\)\.\.(\\|$)' -or
                $name.Contains(':')) {
                throw "Unsafe path in release archive: $name"
            }
            $totalLength += [long]$entry.Length
            if ($totalLength -gt 2147483648) {
                throw 'Release archive expands beyond the 2 GiB safety limit.'
            }
            if (-not $name.EndsWith('\')) {
                if ($entryNames.ContainsKey($name.ToLowerInvariant())) {
                    throw "Duplicate release archive entry: $name"
                }
                $entryNames[$name.ToLowerInvariant()] = $true
            }
            if ($name -ieq 'manifest.json') {
                $manifestEntry = $entry
            }
        }

        if ($null -eq $manifestEntry) {
            throw 'Release archive has no root manifest.json.'
        }

        $reader = New-Object System.IO.StreamReader($manifestEntry.Open(), [System.Text.Encoding]::UTF8, $true)
        try {
            $manifest = $reader.ReadToEnd() | ConvertFrom-Json
        }
        finally {
            $reader.Dispose()
        }

        if ($manifest.schemaVersion -ne 1) {
            throw "Unsupported release manifest schema: $($manifest.schemaVersion)"
        }
        Assert-SafeVersion -Version ([string]$manifest.version)
        foreach ($file in $manifest.files) {
            $manifestName = ([string]$file.path).Replace('/', '\').ToLowerInvariant()
            if (-not $entryNames.ContainsKey($manifestName)) {
                throw "Archive is missing a manifest file: $($file.path)"
            }
        }

        return $manifest
    }
    finally {
        $zip.Dispose()
    }
}

function Expand-VerifiedReleaseArchive {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ArchivePath,

        [Parameter(Mandatory = $true)]
        [string]$DestinationPath
    )

    $destination = Resolve-SafeAbsolutePath -Path $DestinationPath -Label 'DestinationPath'
    if (Test-Path -LiteralPath $destination) {
        throw "Release destination already exists: $destination"
    }
    New-Item -ItemType Directory -Path $destination -Force | Out-Null

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead([System.IO.Path]::GetFullPath($ArchivePath))
    try {
        foreach ($entry in $zip.Entries) {
            $relativePath = $entry.FullName.Replace('/', '\')
            if ([string]::IsNullOrWhiteSpace($relativePath)) {
                continue
            }
            $targetPath = [System.IO.Path]::GetFullPath((Join-Path $destination $relativePath))
            Assert-PathInside -ParentPath $destination -ChildPath $targetPath -Label 'Archive entry'
            if ($relativePath.EndsWith('\')) {
                New-Item -ItemType Directory -Path $targetPath -Force | Out-Null
                continue
            }
            $targetDirectory = Split-Path -Parent $targetPath
            New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
            $sourceStream = $entry.Open()
            try {
                $targetStream = New-Object System.IO.FileStream(
                    $targetPath,
                    [System.IO.FileMode]::CreateNew,
                    [System.IO.FileAccess]::Write,
                    [System.IO.FileShare]::None
                )
                try {
                    $sourceStream.CopyTo($targetStream)
                }
                finally {
                    $targetStream.Dispose()
                }
            }
            finally {
                $sourceStream.Dispose()
            }
        }
    }
    finally {
        $zip.Dispose()
    }

    return Test-ReleaseDirectory -ReleaseRoot $destination
}

function Get-ReleasePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DeploymentRoot,

        [Parameter(Mandatory = $true)]
        [string]$Version
    )

    Assert-SafeVersion -Version $Version
    $root = Resolve-SafeAbsolutePath -Path $DeploymentRoot -Label 'DeploymentRoot'
    $releasesRoot = Join-Path $root 'releases'
    $releasePath = [System.IO.Path]::GetFullPath((Join-Path $releasesRoot $Version))
    Assert-PathInside -ParentPath $releasesRoot -ChildPath $releasePath -Label 'Release path'
    return $releasePath
}

function Write-ReleaseReadyMarker {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReleasePath
    )

    $manifest = Test-ReleaseDirectory -ReleaseRoot $ReleasePath
    $manifestPath = Join-Path $ReleasePath 'manifest.json'
    $marker = [ordered]@{
        schemaVersion  = 1
        version        = [string]$manifest.version
        preparedAtUtc  = [DateTime]::UtcNow.ToString('o')
        manifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $marker | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ReleasePath 'release-state.json') -Encoding UTF8
}

function Test-ReleaseReady {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReleasePath
    )

    $manifest = Test-ReleaseDirectory -ReleaseRoot $ReleasePath
    $markerPath = Join-Path $ReleasePath 'release-state.json'
    if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
        throw "Release is not marked ready: $ReleasePath"
    }
    $marker = Get-Content -LiteralPath $markerPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($marker.schemaVersion -ne 1 -or [string]$marker.version -ne [string]$manifest.version) {
        throw "Release ready marker does not match manifest: $ReleasePath"
    }
    $manifestHash = (Get-FileHash -LiteralPath (Join-Path $ReleasePath 'manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    if ([string]$marker.manifestSha256 -ne $manifestHash) {
        throw "Release ready marker hash does not match manifest: $ReleasePath"
    }
    $aiPython = Join-Path $ReleasePath 'ai-service\.venv\Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $aiPython -PathType Leaf)) {
        throw "Prepared AI runtime is missing: $aiPython"
    }
    return $manifest
}

function New-CurrentJunction {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LinkPath,

        [Parameter(Mandatory = $true)]
        [string]$TargetPath
    )

    if (Test-Path -LiteralPath $LinkPath) {
        throw "Junction path already exists: $LinkPath"
    }
    if (-not (Test-Path -LiteralPath $TargetPath -PathType Container)) {
        throw "Junction target does not exist: $TargetPath"
    }
    New-Item -ItemType Junction -Path $LinkPath -Target $TargetPath | Out-Null
}

function Remove-VerifiedJunction {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.LinkType -ne 'Junction') {
        throw "Refusing to remove a non-junction path: $Path"
    }
    Remove-Item -LiteralPath $Path -Force
}

function Switch-CurrentRelease {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DeploymentRoot,

        [Parameter(Mandatory = $true)]
        [string]$TargetReleasePath,

        [string]$HealthUri = 'http://127.0.0.1:8080/actuator/health'
    )

    $root = Resolve-SafeAbsolutePath -Path $DeploymentRoot -Label 'DeploymentRoot'
    $releasesRoot = Join-Path $root 'releases'
    $target = [System.IO.Path]::GetFullPath($TargetReleasePath)
    Assert-PathInside -ParentPath $releasesRoot -ChildPath $target -Label 'Target release'
    Test-ReleaseReady -ReleasePath $target | Out-Null

    $currentPath = Join-Path $root 'current'
    $nextPath = Join-Path $root 'current.next'
    $previousPath = Join-Path $root 'current.previous'
    if (-not (Test-Path -LiteralPath $currentPath)) {
        throw "Current release junction does not exist: $currentPath"
    }
    $currentItem = Get-Item -LiteralPath $currentPath -Force
    if ($currentItem.LinkType -ne 'Junction') {
        throw "Current path is not a managed junction: $currentPath"
    }
    $currentTarget = @($currentItem.Target)[0]
    if ($currentTarget -and [System.IO.Path]::GetFullPath([string]$currentTarget) -eq $target) {
        throw "Target release is already current: $target"
    }
    if (Test-Path -LiteralPath $nextPath) {
        throw "Stale next junction requires operator review: $nextPath"
    }
    if (Test-Path -LiteralPath $previousPath) {
        throw "Stale previous junction requires operator review: $previousPath"
    }

    $servicesToRestart = @()
    foreach ($serviceId in $script:ThreeFeesServiceIds) {
        $service = Get-Service -Name $serviceId -ErrorAction SilentlyContinue
        if ($null -ne $service -and $service.Status -eq 'Running') {
            $servicesToRestart += $serviceId
        }
    }

    New-CurrentJunction -LinkPath $nextPath -TargetPath $target
    $currentMoved = $false
    try {
        Stop-ThreeFeesServices
        Move-Item -LiteralPath $currentPath -Destination $previousPath
        $currentMoved = $true
        Move-Item -LiteralPath $nextPath -Destination $currentPath
        Start-ThreeFeesServices -ServiceIds $servicesToRestart
        Wait-ThreeFeesHealth -HealthUri $HealthUri
        Remove-VerifiedJunction -Path $previousPath
    }
    catch {
        $switchError = $_
        try {
            Stop-ThreeFeesServices
            if (Test-Path -LiteralPath $currentPath) {
                Remove-VerifiedJunction -Path $currentPath
            }
            if ($currentMoved -and (Test-Path -LiteralPath $previousPath)) {
                Move-Item -LiteralPath $previousPath -Destination $currentPath
                Start-ThreeFeesServices -ServiceIds $servicesToRestart
                Wait-ThreeFeesHealth -HealthUri $HealthUri
            }
        }
        catch {
            throw "Release switch failed and automatic rollback also failed. Original error: $($switchError.Exception.Message). Rollback error: $($_.Exception.Message)"
        }
        throw "Release switch failed; previous release was restored. Error: $($switchError.Exception.Message)"
    }
    finally {
        if (Test-Path -LiteralPath $nextPath) {
            Remove-VerifiedJunction -Path $nextPath
        }
    }
}

function Stop-ThreeFeesServices {
    foreach ($serviceId in $script:ThreeFeesServiceIds) {
        $service = Get-Service -Name $serviceId -ErrorAction SilentlyContinue
        if ($null -ne $service -and $service.Status -ne 'Stopped') {
            Stop-Service -Name $serviceId -Force
            $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(90))
        }
    }
}

function Start-ThreeFeesServices {
    param(
        [string[]]$ServiceIds = @('three-fees-ai', 'three-fees-api', 'three-fees-worker')
    )

    foreach ($serviceId in @('three-fees-ai', 'three-fees-api', 'three-fees-worker')) {
        if ($serviceId -notin $ServiceIds) {
            continue
        }
        $service = Get-Service -Name $serviceId -ErrorAction SilentlyContinue
        if ($null -ne $service -and $service.Status -ne 'Running') {
            Start-Service -Name $serviceId
            $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
        }
    }
}

function Resolve-ReadableReportFont {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReportFontPath
    )

    if (-not [System.IO.Path]::IsPathRooted($ReportFontPath)) {
        throw "REPORT_FONT_PATH must be absolute: $ReportFontPath"
    }
    $fontPath = [System.IO.Path]::GetFullPath($ReportFontPath)
    if (-not (Test-Path -LiteralPath $fontPath -PathType Leaf)) {
        throw "REPORT_FONT_PATH does not exist: $fontPath"
    }
    if ([System.IO.Path]::GetExtension($fontPath).ToLowerInvariant() -notin @('.ttf', '.otf')) {
        throw "REPORT_FONT_PATH must identify a PDFBox-loadable .ttf or .otf file: $fontPath"
    }
    $stream = [System.IO.File]::Open(
        $fontPath,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::ReadWrite
    )
    $stream.Dispose()
    return $fontPath
}

function Wait-ThreeFeesHealth {
    param(
        [string]$HealthUri = 'http://127.0.0.1:8080/actuator/health',
        [int]$TimeoutSeconds = 120
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $HealthUri -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                return
            }
        }
        catch {
            # Service startup is retried until the bounded deadline.
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "API health check did not become ready within $TimeoutSeconds seconds: $HealthUri"
}

function Initialize-AiVirtualEnvironment {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ReleasePath,

        [Parameter(Mandatory = $true)]
        [string]$PythonExe,

        [string]$Wheelhouse
    )

    $aiRoot = Join-Path $ReleasePath 'ai-service'
    $venvRoot = Join-Path $aiRoot '.venv'
    if (Test-Path -LiteralPath $venvRoot) {
        throw "AI virtual environment already exists: $venvRoot"
    }

    Invoke-CheckedProcess -FilePath $PythonExe -ArgumentList @('-m', 'venv', $venvRoot)
    $venvPython = Join-Path $venvRoot 'Scripts\python.exe'
    if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
        throw "AI virtual environment was not created: $venvPython"
    }

    $installArguments = @('-m', 'pip', 'install', '--disable-pip-version-check', '--require-virtualenv')
    if ($Wheelhouse) {
        $wheelhousePath = Resolve-SafeAbsolutePath -Path $Wheelhouse -Label 'Wheelhouse'
        if (-not (Test-Path -LiteralPath $wheelhousePath -PathType Container)) {
            throw "Wheelhouse directory does not exist: $wheelhousePath"
        }
        $installArguments += @('--no-index', '--find-links', $wheelhousePath)
    }

    $lockFile = Join-Path $aiRoot 'requirements.lock'
    $requirementsFile = Join-Path $aiRoot 'requirements.txt'
    if (Test-Path -LiteralPath $lockFile -PathType Leaf) {
        $installArguments += @('-r', $lockFile)
    }
    elseif (Test-Path -LiteralPath $requirementsFile -PathType Leaf) {
        Write-Warning 'AI dependencies are not in requirements.lock; release reproducibility is reduced.'
        $installArguments += @('-r', $requirementsFile)
    }
    elseif (Test-Path -LiteralPath (Join-Path $aiRoot 'pyproject.toml') -PathType Leaf) {
        Write-Warning 'AI dependencies are resolved from pyproject.toml; use a locked requirements file before production.'
        $installArguments += @($aiRoot)
    }
    else {
        throw 'No supported AI dependency metadata was found.'
    }

    Invoke-CheckedProcess -FilePath $venvPython -ArgumentList $installArguments -WorkingDirectory $aiRoot
    Invoke-CheckedProcess -FilePath $venvPython -ArgumentList @('-c', 'import app.main; import uvicorn') -WorkingDirectory $aiRoot
}

function Convert-SecureStringToPlainText {
    param(
        [Parameter(Mandatory = $true)]
        [Security.SecureString]$SecureValue
    )

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}
