[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$violations = [System.Collections.Generic.List[string]]::new()

$forbiddenPathSegments = @(
    '/node_modules/',
    '/.venv/',
    '/venv/',
    '/uploads/',
    '/backups/'
)

$forbiddenSecretFileNames = @(
    '.env',
    'id_rsa',
    'id_ed25519'
)

$textExtensions = @(
    '.bat', '.cmd', '.conf', '.config', '.css', '.env', '.example', '.html', '.ini',
    '.java', '.js', '.json', '.md', '.mjs', '.properties', '.ps1', '.psd1', '.py',
    '.scss', '.sql', '.toml', '.ts', '.tsx', '.txt', '.vue', '.xml', '.yaml', '.yml'
)

$secretPatterns = @(
    @{ Name = 'private key material'; Regex = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----' },
    @{ Name = 'provider-style API key'; Regex = '\bsk-[A-Za-z0-9_-]{20,}\b' },
    @{ Name = 'credential-bearing URL'; Regex = '(?i)\b(?:mysql|postgres(?:ql)?|https?)://[^/\s:@]+:[^@\s/]+@' }
)

# This file deliberately contains a synthetic credential URL to prove deployment preflight rejects it.
$credentialUrlFixtureAllowList = @(
    'deploy/windows/scripts/Test-DeploymentScripts.ps1'
)

Push-Location $repositoryRoot
try {
    $files = @(git ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to enumerate repository files.'
    }

    foreach ($relativePath in $files) {
        if ([string]::IsNullOrWhiteSpace($relativePath)) {
            continue
        }

        $normalizedPath = '/' + $relativePath.Replace('\', '/')
        foreach ($segment in $forbiddenPathSegments) {
            if ($normalizedPath.IndexOf($segment, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $violations.Add("Forbidden generated/runtime path: $relativePath")
                break
            }
        }

        $leafName = [System.IO.Path]::GetFileName($relativePath)
        if ($forbiddenSecretFileNames -contains $leafName) {
            $violations.Add("Forbidden secret-bearing filename: $relativePath")
        }

        $extension = [System.IO.Path]::GetExtension($relativePath).ToLowerInvariant()
        if ($extension -in @('.key', '.p12', '.pfx', '.jks')) {
            $violations.Add("Forbidden key store/private key file: $relativePath")
        }

        if ($extension -notin $textExtensions) {
            continue
        }

        $absolutePath = Join-Path $repositoryRoot $relativePath
        if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
            continue
        }

        $content = Get-Content -Raw -Encoding utf8 -LiteralPath $absolutePath
        foreach ($pattern in $secretPatterns) {
            if ($content -match $pattern.Regex) {
                $normalizedRelativePath = $relativePath.Replace('\', '/')
                if (
                    $pattern.Name -eq 'credential-bearing URL' -and
                    $normalizedRelativePath -in $credentialUrlFixtureAllowList
                ) {
                    continue
                }
                $violations.Add("Possible $($pattern.Name): $relativePath")
            }
        }
    }
}
finally {
    Pop-Location
}

if ($violations.Count -gt 0) {
    $violations | Sort-Object -Unique | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "Repository policy check passed for $($files.Count) files."
