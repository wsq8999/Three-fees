Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:OpsRootMarkerName = '.three-fees-ops-root.json'
$script:OpsBackupMetadataName = 'backup-metadata.json'
$script:OpsBackupManifestName = 'manifest.json'
$script:OpsBackupReadyName = 'READY.json'

function Get-OpsUtcTimestamp {
    return [DateTime]::UtcNow.ToString('o')
}

function Resolve-OpsAbsolutePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Path]::IsPathRooted($Path)) {
        throw "$Label must be an absolute path."
    }

    $fullPath = [IO.Path]::GetFullPath($Path)
    $rootPath = [IO.Path]::GetPathRoot($fullPath)
    if (-not [string]::Equals($fullPath, $rootPath, [StringComparison]::OrdinalIgnoreCase)) {
        $fullPath = $fullPath.TrimEnd([char[]]@('\', '/'))
    }
    return $fullPath
}

function Assert-OpsManagedRootCandidate {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $fullPath = Resolve-OpsAbsolutePath -Path $Path -Label $Label
    $rootPath = [IO.Path]::GetPathRoot($fullPath)
    if ([string]::Equals($fullPath.TrimEnd('\'), $rootPath.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label cannot be a drive or share root."
    }
    return $fullPath
}

function Test-OpsPathInside {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ParentPath,

        [Parameter(Mandatory = $true)]
        [string]$ChildPath
    )

    $parent = (Resolve-OpsAbsolutePath -Path $ParentPath -Label 'ParentPath').TrimEnd('\') + '\'
    $child = Resolve-OpsAbsolutePath -Path $ChildPath -Label 'ChildPath'
    return $child.StartsWith($parent, [StringComparison]::OrdinalIgnoreCase)
}

function Assert-OpsPathInside {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ParentPath,

        [Parameter(Mandatory = $true)]
        [string]$ChildPath,

        [string]$Label = 'Path'
    )

    if (-not (Test-OpsPathInside -ParentPath $ParentPath -ChildPath $ChildPath)) {
        throw "$Label escapes its configured root."
    }
}

function Test-OpsPathsOverlap {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FirstPath,

        [Parameter(Mandatory = $true)]
        [string]$SecondPath
    )

    $first = Resolve-OpsAbsolutePath -Path $FirstPath -Label 'FirstPath'
    $second = Resolve-OpsAbsolutePath -Path $SecondPath -Label 'SecondPath'
    return [string]::Equals($first, $second, [StringComparison]::OrdinalIgnoreCase) -or
        (Test-OpsPathInside -ParentPath $first -ChildPath $second) -or
        (Test-OpsPathInside -ParentPath $second -ChildPath $first)
}

function Assert-OpsNotReparsePoint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (Test-Path -LiteralPath $Path) {
        $item = Get-Item -LiteralPath $Path -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Label cannot be a reparse point."
        }
    }
}

function Get-OperationsConfig {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ConfigPath
    )

    $fullPath = Resolve-OpsAbsolutePath -Path $ConfigPath -Label 'ConfigPath'
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Operations config does not exist: $fullPath"
    }

    $config = Import-PowerShellDataFile -LiteralPath $fullPath
    if ([int]$config.SchemaVersion -ne 1) {
        throw 'Unsupported operations config schema.'
    }
    foreach ($name in @('DeploymentRoot', 'FileRoot', 'LogRoot', 'BackupRoot', 'RestoreDrillRoot')) {
        [void](Resolve-OpsAbsolutePath -Path ([string]$config.Paths[$name]) -Label "Paths.$name")
    }
    foreach ($name in @('BackupUsernameEnvironmentVariable', 'BackupPasswordEnvironmentVariable', 'RestoreUsernameEnvironmentVariable', 'RestorePasswordEnvironmentVariable')) {
        if ([string]$config.Database[$name] -notmatch '^[A-Z][A-Z0-9_]{2,63}$') {
            throw "Database.$name must be an environment variable name."
        }
    }
    if ([int]$config.Backup.DailyRetentionCount -lt 1 -or [int]$config.Backup.WeeklyRetentionCount -lt 1) {
        throw 'Backup retention counts must be positive.'
    }
    if ([double]$config.Thresholds.RpoHours -le 0 -or [double]$config.Thresholds.RtoHours -le 0) {
        throw 'Recovery objective thresholds must be positive.'
    }
    return $config
}

function Write-OpsJsonFile {
    param(
        [Parameter(Mandatory = $true)]
        [object]$InputObject,

        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $json = $InputObject | ConvertTo-Json -Depth 12
    [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
}

function Get-OpsRootMarkerPath {
    param([Parameter(Mandatory = $true)][string]$RootPath)
    return Join-Path $RootPath $script:OpsRootMarkerName
}

function Assert-OpsRootMarker {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RootPath,

        [Parameter(Mandatory = $true)]
        [ValidateSet('BackupRoot', 'RestoreDrillRoot')]
        [string]$Purpose
    )

    $root = Assert-OpsManagedRootCandidate -Path $RootPath -Label $Purpose
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        throw "$Purpose does not exist. Initialize it explicitly first."
    }
    Assert-OpsNotReparsePoint -Path $root -Label $Purpose
    $markerPath = Get-OpsRootMarkerPath -RootPath $root
    if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
        throw "$Purpose is not marked as a ThreeFees managed root."
    }
    $marker = Get-Content -LiteralPath $markerPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([int]$marker.schemaVersion -ne 1 -or [string]$marker.purpose -ne $Purpose) {
        throw "$Purpose marker is invalid."
    }
    $markedRoot = Resolve-OpsAbsolutePath -Path ([string]$marker.rootPath) -Label 'Marker rootPath'
    if (-not [string]::Equals($root, $markedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Purpose marker belongs to a different path."
    }
    return $root
}

function Get-OpsEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    return [Environment]::GetEnvironmentVariable($Name, [EnvironmentVariableTarget]::Process)
}

function Get-OpsSecret {
    param(
        [Parameter(Mandatory = $true)]
        [string]$EnvironmentVariableName,

        [Parameter(Mandatory = $true)]
        [string]$Prompt,

        [switch]$AllowPrompt
    )

    $value = Get-OpsEnvironmentValue -Name $EnvironmentVariableName
    if (-not [string]::IsNullOrWhiteSpace($value)) {
        return $value
    }
    if (-not $AllowPrompt) {
        throw "Required secret environment variable is not set: $EnvironmentVariableName"
    }

    $secureValue = Read-Host -Prompt $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function ConvertTo-OpsCommandLineArgument {
    param([AllowEmptyString()][string]$Value)

    if ($Value -notmatch '[\s"]' -and $Value.Length -gt 0) {
        return $Value
    }
    $builder = New-Object Text.StringBuilder
    [void]$builder.Append('"')
    $backslashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $backslashes++
            continue
        }
        if ($character -eq '"') {
            [void]$builder.Append(('\' * (($backslashes * 2) + 1)))
            [void]$builder.Append('"')
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) {
            [void]$builder.Append(('\' * $backslashes))
            $backslashes = 0
        }
        [void]$builder.Append($character)
    }
    if ($backslashes -gt 0) {
        [void]$builder.Append(('\' * ($backslashes * 2)))
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-OpsExternalProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$Arguments = @(),

        [string]$StandardInputPath,

        [string]$StandardOutputPath,

        [string]$StandardErrorPath
    )

    if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
        throw "Required executable does not exist: $FilePath"
    }
    $argumentLine = (($Arguments | ForEach-Object { ConvertTo-OpsCommandLineArgument -Value ([string]$_) }) -join ' ')
    $startParameters = @{
        FilePath = $FilePath
        ArgumentList = $argumentLine
        Wait = $true
        PassThru = $true
        NoNewWindow = $true
    }
    if ($StandardInputPath) { $startParameters.RedirectStandardInput = $StandardInputPath }
    if ($StandardOutputPath) { $startParameters.RedirectStandardOutput = $StandardOutputPath }
    if ($StandardErrorPath) { $startParameters.RedirectStandardError = $StandardErrorPath }
    $process = Start-Process @startParameters
    return [int]$process.ExitCode
}

function Invoke-OpsRobocopy {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourcePath,

        [Parameter(Mandatory = $true)]
        [string]$DestinationPath,

        [Parameter(Mandatory = $true)]
        [string]$LogPath
    )

    $robocopy = Join-Path $env:SystemRoot 'System32\robocopy.exe'
    $arguments = @(
        $SourcePath,
        $DestinationPath,
        '/E', '/COPY:DAT', '/DCOPY:DAT', '/R:2', '/W:2', '/XJ',
        '/NP', '/NFL', '/NDL', '/NJH', '/NJS'
    )
    $errorLogPath = "$LogPath.stderr"
    $exitCode = Invoke-OpsExternalProcess -FilePath $robocopy -Arguments $arguments -StandardOutputPath $LogPath -StandardErrorPath $errorLogPath
    if ($exitCode -gt 7) {
        throw "File snapshot failed with robocopy exit code $exitCode."
    }
    return $exitCode
}

function Get-OpsRelativePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RootPath,

        [Parameter(Mandatory = $true)]
        [string]$FilePath
    )

    $root = (Resolve-OpsAbsolutePath -Path $RootPath -Label 'RootPath').TrimEnd('\') + '\'
    $file = Resolve-OpsAbsolutePath -Path $FilePath -Label 'FilePath'
    if (-not $file.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'File path is outside the manifest root.'
    }
    return $file.Substring($root.Length).Replace('\', '/')
}

function New-OpsPayloadManifestEntries {
    param([Parameter(Mandatory = $true)][string]$PayloadRoot)

    $entries = @()
    foreach ($file in @(Get-ChildItem -LiteralPath $PayloadRoot -Recurse -File | Sort-Object FullName)) {
        $entries += [ordered]@{
            path = Get-OpsRelativePath -RootPath $PayloadRoot -FilePath $file.FullName
            length = [long]$file.Length
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    return $entries
}

function Get-OpsBackupArtifactDirectories {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BackupRoot,

        [Parameter(Mandatory = $true)]
        [ValidateSet('Daily', 'Weekly')]
        [string]$BackupClass
    )

    $root = Assert-OpsRootMarker -RootPath $BackupRoot -Purpose BackupRoot
    $classRoot = Join-Path $root $BackupClass.ToLowerInvariant()
    if (-not (Test-Path -LiteralPath $classRoot -PathType Container)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $classRoot -Directory -Force | Where-Object {
        $_.Name -match '^[0-9]{8}T[0-9]{9}Z-[0-9a-f]{8}$'
    })
}

function Test-OpsBackupArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BackupRoot,

        [Parameter(Mandatory = $true)]
        [string]$ArtifactPath,

        [switch]$VerifyPayload
    )

    $root = Assert-OpsRootMarker -RootPath $BackupRoot -Purpose BackupRoot
    $artifact = Resolve-OpsAbsolutePath -Path $ArtifactPath -Label 'ArtifactPath'
    Assert-OpsPathInside -ParentPath $root -ChildPath $artifact -Label 'Backup artifact'
    Assert-OpsNotReparsePoint -Path $artifact -Label 'Backup artifact'
    if (-not (Test-Path -LiteralPath $artifact -PathType Container)) {
        throw 'Backup artifact does not exist.'
    }
    $parentName = Split-Path -Path (Split-Path -Path $artifact -Parent) -Leaf
    if ($parentName -notin @('daily', 'weekly')) {
        throw 'Backup artifact must be a direct child of daily or weekly.'
    }
    $classRoot = Join-Path $root $parentName
    $actualParent = Resolve-OpsAbsolutePath -Path (Split-Path -Path $artifact -Parent) -Label 'Artifact parent'
    if (-not [string]::Equals($classRoot, $actualParent, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Backup artifact is not a direct class child.'
    }

    $metadataPath = Join-Path $artifact $script:OpsBackupMetadataName
    $manifestPath = Join-Path $artifact $script:OpsBackupManifestName
    $readyPath = Join-Path $artifact $script:OpsBackupReadyName
    foreach ($path in @($metadataPath, $manifestPath, $readyPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Backup proof file is missing: $(Split-Path -Path $path -Leaf)"
        }
    }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $ready = Get-Content -LiteralPath $readyPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $artifactId = Split-Path -Path $artifact -Leaf
    if ([int]$metadata.schemaVersion -ne 1 -or [string]$metadata.status -ne 'Complete' -or
        [string]$metadata.backupId -ne $artifactId -or [string]$manifest.backupId -ne $artifactId -or
        [string]$ready.backupId -ne $artifactId) {
        throw 'Backup proof identifiers are inconsistent.'
    }
    $metadataHash = (Get-FileHash -LiteralPath $metadataPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($metadataHash -ne ([string]$ready.metadataSha256).ToLowerInvariant() -or
        $manifestHash -ne ([string]$ready.manifestSha256).ToLowerInvariant()) {
        throw 'Backup proof hashes do not match.'
    }

    if ($VerifyPayload) {
        foreach ($entry in @($manifest.entries)) {
            $relativePath = ([string]$entry.path).Replace('/', '\')
            if ([string]::IsNullOrWhiteSpace($relativePath) -or [IO.Path]::IsPathRooted($relativePath) -or
                $relativePath -match '(^|\\)\.\.($|\\)') {
                throw 'Backup manifest contains an unsafe path.'
            }
            $filePath = [IO.Path]::GetFullPath((Join-Path $artifact $relativePath))
            Assert-OpsPathInside -ParentPath $artifact -ChildPath $filePath -Label 'Manifest payload'
            if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
                throw "Backup payload is missing: $relativePath"
            }
            $file = Get-Item -LiteralPath $filePath
            if ([long]$entry.length -ne [long]$file.Length) {
                throw "Backup payload length mismatch: $relativePath"
            }
            $hash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($hash -ne ([string]$entry.sha256).ToLowerInvariant()) {
                throw "Backup payload hash mismatch: $relativePath"
            }
        }
    }
    return $metadata
}

function Remove-OpsManagedBackupArtifact {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(Mandatory = $true)]
        [string]$BackupRoot,

        [Parameter(Mandatory = $true)]
        [string]$ArtifactPath,

        [switch]$Apply
    )

    $metadata = Test-OpsBackupArtifact -BackupRoot $BackupRoot -ArtifactPath $ArtifactPath
    if (-not $Apply) {
        return [pscustomobject]@{ action = 'WouldRemove'; backupId = $metadata.backupId; path = $ArtifactPath }
    }
    if ($PSCmdlet.ShouldProcess($ArtifactPath, 'Remove validated expired backup artifact')) {
        Remove-Item -LiteralPath $ArtifactPath -Recurse -Force
        return [pscustomobject]@{ action = 'Removed'; backupId = $metadata.backupId; path = $ArtifactPath }
    }
}

function Get-OpsDirectoryStats {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        return [pscustomobject]@{ fileCount = 0; totalBytes = 0; newestWriteUtc = $null }
    }
    $files = @(Get-ChildItem -LiteralPath $Path -Recurse -File -ErrorAction Stop)
    $totalBytes = [long]0
    $newest = $null
    foreach ($file in $files) {
        $totalBytes += [long]$file.Length
        if ($null -eq $newest -or $file.LastWriteTimeUtc -gt $newest) { $newest = $file.LastWriteTimeUtc }
    }
    return [pscustomobject]@{ fileCount = $files.Count; totalBytes = $totalBytes; newestWriteUtc = $newest }
}

function New-OpsCheckResult {
    param(
        [Parameter(Mandatory = $true)][string]$Check,
        [Parameter(Mandatory = $true)][ValidateSet('Pass', 'Warning', 'Fail')][string]$Status,
        [Parameter(Mandatory = $true)][string]$Message,
        [object]$Value
    )
    return [pscustomobject]@{ check = $Check; status = $Status; message = $Message; value = $Value }
}
