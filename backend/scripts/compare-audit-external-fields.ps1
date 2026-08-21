param(
  [string]$HostName = "127.0.0.1",
  [string]$Database = "three_fees",
  [string]$User = "root",
  [string]$Password = "547547",
  [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $OutputPath = Join-Path $PSScriptRoot "..\runtime\audit-diagnostics\audit-compare-$stamp.csv"
}

$outputDirectory = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $outputDirectory)) {
  New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}

$sql = @"
SELECT
  p.billing_point_name,
  p.billing_point_code,
  p.city_code,
  p.data_period,
  ROUND(SUM(COALESCE(p.actual_total_kwh, p.daily_avg_kwh * p.payment_days)), 6) AS payment_total_kwh,
  ROUND(SUM(COALESCE(p.actual_report_amount, 0)), 6) AS payment_amount,
  MAX(p.payment_days) AS payment_days,
  ROUND(MAX(p.historical_daily_energy_yoy), 6) AS external_yoy_ratio,
  ROUND(MAX(p.historical_daily_energy_mom), 6) AS external_mom_ratio,
  ROUND(MAX(p.rated_power_benchmark), 6) AS external_rated_ratio,
  ar.audit_status,
  ar.over_limit_type,
  ROUND(ar.current_daily_avg_kwh, 6) AS system_current_daily,
  ar.yoy_result,
  ROUND(ar.yoy_ratio, 6) AS system_yoy_ratio,
  ROUND(ar.yoy_threshold_daily_kwh, 6) AS system_yoy_threshold,
  ar.yoy_reference_period,
  ar.mom_result,
  ROUND(ar.mom_ratio, 6) AS system_mom_ratio,
  ROUND(ar.mom_threshold_daily_kwh, 6) AS system_mom_threshold,
  ar.mom_reference_period,
  ar.rated_result,
  ROUND(ar.rated_ratio, 6) AS system_rated_ratio,
  ROUND(ar.rated_total_kwh, 6) AS system_rated_threshold,
  CASE WHEN MAX(p.historical_daily_energy_yoy) IS NULL THEN 'NORMAL' ELSE 'OVER_LIMIT' END AS external_yoy_result,
  CASE WHEN MAX(p.historical_daily_energy_mom) IS NULL THEN 'NORMAL' ELSE 'OVER_LIMIT' END AS external_mom_result,
  CASE WHEN MAX(p.rated_power_benchmark) IS NULL THEN 'NORMAL' ELSE 'OVER_LIMIT' END AS external_rated_result,
  CASE
    WHEN MAX(p.historical_daily_energy_yoy) IS NULL AND COALESCE(ar.yoy_result, 'NA') <> 'OVER_LIMIT' THEN 'MATCH'
    WHEN MAX(p.historical_daily_energy_yoy) IS NOT NULL AND ar.yoy_result = 'OVER_LIMIT' THEN 'MATCH'
    ELSE 'MISMATCH'
  END AS yoy_compare,
  CASE
    WHEN MAX(p.historical_daily_energy_mom) IS NULL AND COALESCE(ar.mom_result, 'NA') <> 'OVER_LIMIT' THEN 'MATCH'
    WHEN MAX(p.historical_daily_energy_mom) IS NOT NULL AND ar.mom_result = 'OVER_LIMIT' THEN 'MATCH'
    ELSE 'MISMATCH'
  END AS mom_compare,
  CASE
    WHEN MAX(p.rated_power_benchmark) IS NULL AND COALESCE(ar.rated_result, 'NA') <> 'OVER_LIMIT' THEN 'MATCH'
    WHEN MAX(p.rated_power_benchmark) IS NOT NULL AND ar.rated_result = 'OVER_LIMIT' THEN 'MATCH'
    ELSE 'MISMATCH'
  END AS rated_compare
FROM payment_detail p
LEFT JOIN audit_result ar
  ON ar.city_code = p.city_code
 AND ar.billing_point_code = p.billing_point_code
 AND ar.data_period = p.data_period
WHERE p.billing_point_code IN (
  'ZDBZD-JS-2020-014387',
  'ZDBZD-JS-2018-1018812',
  'ZDBZD-JS-2018-1022442'
)
GROUP BY p.billing_point_name, p.billing_point_code, p.city_code, p.data_period,
         ar.audit_status, ar.over_limit_type, ar.current_daily_avg_kwh,
         ar.yoy_result, ar.yoy_ratio, ar.yoy_threshold_daily_kwh, ar.yoy_reference_period,
         ar.mom_result, ar.mom_ratio, ar.mom_threshold_daily_kwh, ar.mom_reference_period,
         ar.rated_result, ar.rated_ratio, ar.rated_total_kwh
ORDER BY p.billing_point_name, p.data_period
"@

$headers = @(
  "billing_point_name", "billing_point_code", "city_code", "period",
  "payment_total_kwh", "payment_amount", "payment_days",
  "external_yoy_ratio", "external_mom_ratio", "external_rated_ratio",
  "system_audit_status", "system_over_limit_type", "system_current_daily",
  "system_yoy_result", "system_yoy_ratio", "system_yoy_threshold", "system_yoy_reference_period",
  "system_mom_result", "system_mom_ratio", "system_mom_threshold", "system_mom_reference_period",
  "system_rated_result", "system_rated_ratio", "system_rated_threshold",
  "external_yoy_result", "external_mom_result", "external_rated_result",
  "yoy_compare", "mom_compare", "rated_compare"
)

$rawLines = & mysql --host=$HostName --user=$User --password=$Password --default-character-set=utf8mb4 --batch --raw --skip-column-names --execute=$sql $Database
if ($LASTEXITCODE -ne 0) {
  throw "MySQL query failed."
}

$rows = foreach ($line in $rawLines) {
  if ([string]::IsNullOrWhiteSpace($line)) {
    continue
  }
  $cells = $line -split "`t", -1
  $record = [ordered]@{}
  for ($i = 0; $i -lt $headers.Count; $i++) {
    $value = if ($i -lt $cells.Count) { $cells[$i] } else { "" }
    if ($value -eq "\N") {
      $value = ""
    }
    $record[$headers[$i]] = $value
  }
  [pscustomobject]$record
}

$rows | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Encoding UTF8

$mismatches =
  $rows | Where-Object {
    $_.yoy_compare -eq "MISMATCH" -or
    $_.mom_compare -eq "MISMATCH" -or
    $_.rated_compare -eq "MISMATCH"
  }

Write-Host "Audit comparison exported: $OutputPath"
Write-Host "Total rows: $($rows.Count); mismatch rows: $($mismatches.Count)"
if ($mismatches.Count -gt 0) {
  $mismatches |
    Select-Object billing_point_name,period,external_yoy_result,system_yoy_result,yoy_compare,external_mom_result,system_mom_result,mom_compare,external_rated_result,system_rated_result,rated_compare |
    Format-Table -AutoSize
}
