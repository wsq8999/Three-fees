#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseArchive,

    [Parameter(Mandatory = $true)]
    [string]$WinSWExecutable,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$WinSWExpectedSha256,

    [string]$DeploymentRoot = 'C:\ProgramData\ThreeFees',
    [string]$JavaExe = 'C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe',
    [string]$ReportFontPath = 'C:\Windows\Fonts\simhei.ttf',
    [string]$SiteName = 'ThreeFees',
    [string]$AppPoolName = 'ThreeFees',
    [string]$HostName = 'three-fees.local',
    [int]$HttpsPort = 443,
    [int]$HttpPort = 80,
    [string]$CertificateThumbprint,
    [switch]$AllowHttp,
    [switch]$Apply
)

. (Join-Path $PSScriptRoot 'Common.ps1')

function Test-ServerPrerequisites {
    param([switch]$Required)

    $results = @()
    $checks = [ordered]@{
        'IIS WebAdministration module' = [bool](Get-Module -ListAvailable -Name WebAdministration)
        'IIS appcmd.exe'               = Test-Path -LiteralPath (Join-Path $env:windir 'System32\inetsrv\appcmd.exe') -PathType Leaf
        'IIS URL Rewrite module'       = Test-Path -LiteralPath (Join-Path $env:windir 'System32\inetsrv\rewrite.dll') -PathType Leaf
        'IIS ARR request router'       = Test-Path -LiteralPath (Join-Path $env:windir 'System32\inetsrv\requestRouter.dll') -PathType Leaf
    }
    foreach ($entry in $checks.GetEnumerator()) {
        $results += [pscustomobject]@{ Prerequisite = $entry.Key; Present = [bool]$entry.Value }
        if ($Required -and -not $entry.Value) {
            throw "Required server prerequisite is missing: $($entry.Key)"
        }
    }
    return $results
}

function Install-ServiceWrappers {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root,

        [Parameter(Mandatory = $true)]
        [string]$WrapperSource,

        [Parameter(Mandatory = $true)]
        [string]$JavaExecutable,

        [Parameter(Mandatory = $true)]
        [string]$TemplateRoot
    )

    $servicesRoot = Join-Path $Root 'services'
    $escapedRoot = [Security.SecurityElement]::Escape($Root)
    $escapedJava = [Security.SecurityElement]::Escape($JavaExecutable)
    foreach ($serviceId in $script:ThreeFeesServiceIds) {
        if (Get-Service -Name $serviceId -ErrorAction SilentlyContinue) {
            throw "Service is already installed: $serviceId"
        }
        $serviceDirectory = Join-Path $servicesRoot $serviceId
        New-Item -ItemType Directory -Path $serviceDirectory -Force | Out-Null
        $wrapperTarget = Join-Path $serviceDirectory ($serviceId + '.exe')
        $configTarget = Join-Path $serviceDirectory ($serviceId + '.xml')
        Copy-Item -LiteralPath $WrapperSource -Destination $wrapperTarget

        $templatePath = Join-Path $TemplateRoot ($serviceId + '.xml')
        $xmlText = Get-Content -LiteralPath $templatePath -Raw -Encoding UTF8
        $xmlText = $xmlText.Replace('__DEPLOYMENT_ROOT__', $escapedRoot).Replace('__JAVA_EXE__', $escapedJava)
        $xmlText | Set-Content -LiteralPath $configTarget -Encoding UTF8
        [xml](Get-Content -LiteralPath $configTarget -Raw -Encoding UTF8) | Out-Null

        Invoke-CheckedProcess -FilePath $wrapperTarget -ArgumentList @('install') -WorkingDirectory $serviceDirectory
    }
}

function Grant-DeploymentPermissions {
    param([Parameter(Mandatory = $true)][string]$Root)

    $systemSid = '*S-1-5-18'
    $administratorsSid = '*S-1-5-32-544'
    $networkServiceSid = '*S-1-5-20'
    $iisUsersSid = '*S-1-5-32-568'
    Invoke-CheckedProcess -FilePath 'icacls.exe' -ArgumentList @($Root, '/inheritance:r', '/grant:r', "$systemSid`:(OI)(CI)F", "$administratorsSid`:(OI)(CI)F", "$networkServiceSid`:(OI)(CI)RX", "$iisUsersSid`:(OI)(CI)RX")
    foreach ($writeDirectory in @(
        (Join-Path $Root 'shared\files'),
        (Join-Path $Root 'shared\logs'),
        (Join-Path $Root 'shared\tmp')
    )) {
        Invoke-CheckedProcess -FilePath 'icacls.exe' -ArgumentList @($writeDirectory, '/inheritance:r', '/grant:r', "$systemSid`:(OI)(CI)F", "$administratorsSid`:(OI)(CI)F", "$networkServiceSid`:(OI)(CI)M")
    }
    Invoke-CheckedProcess -FilePath 'icacls.exe' -ArgumentList @((Join-Path $Root 'releases'), '/grant', "$networkServiceSid`:(OI)(CI)RX", "$iisUsersSid`:(OI)(CI)RX")
    Invoke-CheckedProcess -FilePath 'icacls.exe' -ArgumentList @((Join-Path $Root 'services'), '/grant', "$networkServiceSid`:(OI)(CI)RX")
}

function Install-IisSite {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$PoolName,
        [Parameter(Mandatory = $true)][string]$HostHeader,
        [int]$TlsPort,
        [int]$PlainPort,
        [string]$Thumbprint,
        [switch]$UseHttp
    )

    Import-Module WebAdministration
    if (Test-Path -LiteralPath ("IIS:\Sites\" + $Name)) {
        throw "IIS site already exists: $Name"
    }
    if (-not (Test-Path -LiteralPath ("IIS:\AppPools\" + $PoolName))) {
        New-WebAppPool -Name $PoolName | Out-Null
        Set-ItemProperty -LiteralPath ("IIS:\AppPools\" + $PoolName) -Name managedRuntimeVersion -Value ''
        Set-ItemProperty -LiteralPath ("IIS:\AppPools\" + $PoolName) -Name processModel.identityType -Value ApplicationPoolIdentity
    }

    $frontendRoot = Join-Path $Root 'current\frontend'
    if ($UseHttp) {
        New-Website -Name $Name -PhysicalPath $frontendRoot -ApplicationPool $PoolName -Port $PlainPort -IPAddress '*' -HostHeader $HostHeader | Out-Null
    }
    else {
        $certificate = Get-Item -LiteralPath ("Cert:\LocalMachine\My\" + $Thumbprint) -ErrorAction Stop
        if ($certificate.NotAfter -le (Get-Date)) {
            throw 'The selected IIS certificate is expired.'
        }
        New-Website -Name $Name -PhysicalPath $frontendRoot -ApplicationPool $PoolName -Port $TlsPort -IPAddress '*' -HostHeader $HostHeader -Ssl -SslFlags 1 | Out-Null
        $binding = Get-WebBinding -Name $Name -Protocol 'https'
        $binding.AddSslCertificate($certificate.Thumbprint, 'My')
    }

    $appcmd = Join-Path $env:windir 'System32\inetsrv\appcmd.exe'
    Invoke-CheckedProcess -FilePath $appcmd -ArgumentList @('set', 'config', '-section:system.webServer/proxy', '/enabled:true', '/commit:apphost')
}

$root = Resolve-SafeAbsolutePath -Path $DeploymentRoot -Label 'DeploymentRoot'
$archivePath = [System.IO.Path]::GetFullPath($ReleaseArchive)
$wrapperPath = [System.IO.Path]::GetFullPath($WinSWExecutable)
$javaPath = [System.IO.Path]::GetFullPath($JavaExe)
$reportFont = Resolve-ReadableReportFont -ReportFontPath $ReportFontPath

$manifest = Test-ReleaseArchive -ArchivePath $archivePath
if (-not (Test-Path -LiteralPath $wrapperPath -PathType Leaf)) {
    throw "WinSW executable does not exist: $wrapperPath"
}
$wrapperHash = (Get-FileHash -LiteralPath $wrapperPath -Algorithm SHA256).Hash
if ($wrapperHash -ne $WinSWExpectedSha256.ToUpperInvariant()) {
    throw 'WinSW SHA-256 does not match the independently verified expected value.'
}
if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) {
    throw "Java executable does not exist: $javaPath"
}
if (-not $AllowHttp -and [string]::IsNullOrWhiteSpace($CertificateThumbprint)) {
    throw 'HTTPS is the default. Provide -CertificateThumbprint or explicitly use -AllowHttp for an isolated trial environment.'
}
if ($CertificateThumbprint -and $CertificateThumbprint -notmatch '^[A-Fa-f0-9]{40,64}$') {
    throw 'Certificate thumbprint has an invalid format.'
}

$serverPrerequisites = Test-ServerPrerequisites -Required:$Apply
$plan = [pscustomobject]@{
    Mode              = $(if ($Apply) { 'Apply' } else { 'PreflightOnly' })
    Version           = [string]$manifest.version
    DeploymentRoot    = $root
    Site              = $SiteName
    Binding           = $(if ($AllowHttp) { "http://$HostName`:$HttpPort" } else { "https://$HostName`:$HttpsPort" })
    Services          = $script:ThreeFeesServiceIds -join ', '
    Prerequisites     = $serverPrerequisites
    ReportFontPath    = $reportFont
}
$plan
if (-not $Apply) {
    Write-Host 'Preflight completed. No IIS, service, registry, ACL, or deployment-directory changes were made.'
    return
}

Assert-Administrator
if (Test-Path -LiteralPath (Join-Path $root 'current')) {
    throw "Deployment already has a current release. Use Upgrade-ThreeFees.ps1: $root"
}

$releasePath = Get-ReleasePath -DeploymentRoot $root -Version ([string]$manifest.version)
if (Test-Path -LiteralPath $releasePath) {
    throw "Release is already installed: $releasePath"
}

foreach ($directory in @(
    $root,
    (Join-Path $root 'releases'),
    (Join-Path $root 'staging'),
    (Join-Path $root 'services'),
    (Join-Path $root 'shared\files'),
    (Join-Path $root 'shared\logs\api'),
    (Join-Path $root 'shared\logs\worker'),
    (Join-Path $root 'shared\tmp\api'),
    (Join-Path $root 'shared\tmp\worker')
)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

$stagingPath = Join-Path (Join-Path $root 'staging') ([Guid]::NewGuid().ToString('N'))
Assert-PathInside -ParentPath (Join-Path $root 'staging') -ChildPath $stagingPath -Label 'Staging path'
try {
    Expand-VerifiedReleaseArchive -ArchivePath $archivePath -DestinationPath $stagingPath | Out-Null
    Move-Item -LiteralPath $stagingPath -Destination $releasePath
    Write-ReleaseReadyMarker -ReleasePath $releasePath
}
finally {
    if (Test-Path -LiteralPath $stagingPath) {
        Assert-PathInside -ParentPath (Join-Path $root 'staging') -ChildPath $stagingPath -Label 'Staging cleanup path'
        Remove-Item -LiteralPath $stagingPath -Recurse -Force
    }
}

New-CurrentJunction -LinkPath (Join-Path $root 'current') -TargetPath $releasePath
Grant-DeploymentPermissions -Root $root
Install-ServiceWrappers -Root $root -WrapperSource $wrapperPath -JavaExecutable $javaPath -TemplateRoot (Join-Path $PSScriptRoot '..\config\winsw')
Install-IisSite -Root $root -Name $SiteName -PoolName $AppPoolName -HostHeader $HostName -TlsPort $HttpsPort -PlainPort $HttpPort -Thumbprint $CertificateThumbprint -UseHttp:$AllowHttp

Write-Host 'Installation completed without starting application services.'
Write-Host 'Next: run Set-ServiceEnvironment.ps1 with secure prompts, then start services and verify health.'
