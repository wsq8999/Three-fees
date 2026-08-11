[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot

$catalogs = @(
    @{
        Name = 'billing-point'
        Path = 'backend\src\main\resources\catalog-billing-point.tsv'
        Rows = 73
        Sha256 = '210c99e93f689e96e1e97841a0d4298d190c21b6a4edc3743dcc1500ac2bd75f'
    },
    @{
        Name = 'payment'
        Path = 'backend\src\main\resources\catalog-payment.tsv'
        Rows = 198
        Sha256 = 'f753cac6e442eb1147941ccc9b34c1996e2722ba42b5f0de64c9f1ff9586600c'
    },
    @{
        Name = 'meter-reading'
        Path = 'backend\src\main\resources\catalog-meter-reading.tsv'
        Rows = 42
        Sha256 = '21c86079ceb9834416ca26c9f86d6e4a10871d321e8a08410ccfb54e5b510aad'
    },
    @{
        Name = 'benchmark'
        Path = 'backend\src\main\resources\catalog-benchmark.tsv'
        Rows = 39
        Sha256 = 'd7aa67a73f0dba9602e76f88b9f61dc4cfbc8e8313485faa3fcb522044225cbf'
    }
)

function Get-CanonicalSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Lines
    )

    $canonicalText = ($Lines -join "`n") + "`n"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($canonicalText)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash($bytes)
    }
    finally {
        $sha256.Dispose()
    }
    return [System.BitConverter]::ToString($hash).Replace('-', '').ToLowerInvariant()
}

foreach ($catalog in $catalogs) {
    $absolutePath = Join-Path $repositoryRoot $catalog.Path
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        throw "Required field catalog is missing: $($catalog.Path)"
    }

    $lines = @(Get-Content -LiteralPath $absolutePath -Encoding UTF8)
    if ($lines.Count -ne $catalog.Rows) {
        throw "$($catalog.Name) catalog must contain exactly $($catalog.Rows) rows; found $($lines.Count)."
    }

    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ([string]::IsNullOrWhiteSpace($lines[$index])) {
            throw "$($catalog.Name) catalog contains a blank row at $($index + 1)."
        }
        $columns = @($lines[$index] -split "`t", -1)
        if ($columns.Count -ne 6) {
            throw "$($catalog.Name) row $($index + 1) must contain six tab-separated columns; found $($columns.Count)."
        }
        if ($columns[0] -ne [string]($index + 1)) {
            throw "$($catalog.Name) row order mismatch at $($index + 1): found '$($columns[0])'."
        }
    }

    $actualHash = Get-CanonicalSha256 -Lines $lines
    if ($actualHash -ne $catalog.Sha256) {
        throw "$($catalog.Name) catalog differs from the approved requirements appendix. Expected $($catalog.Sha256), found $actualHash."
    }

    Write-Output "PASS field-catalog:$($catalog.Name) rows=$($catalog.Rows) sha256=$actualHash"
}

Write-Output 'Field catalog baseline check passed.'
