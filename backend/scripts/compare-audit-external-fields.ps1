param(
  [string]$HostName = "127.0.0.1",
  [string]$Database = "three_fees",
  [string]$User = "root",
  [string]$Password = "547547",
  [string]$SincePeriod = "2024-01",
  [decimal]$TolerancePercent = 0.01,
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
  ROUND(ar.yoy_reference_daily_kwh_c, 6) AS system_yoy_c,
  ROUND(ar.yoy_current_benchmark_avg_a, 6) AS system_yoy_a,
  ROUND(ar.yoy_reference_benchmark_avg_b, 6) AS system_yoy_b,
  ROUND(CAST(JSON_UNQUOTE(JSON_EXTRACT(yoy_ref_benchmark.values_json, '$."1"')) AS DECIMAL(18,6)), 6) AS yoy_reference_benchmark_first_day,
  CASE
    WHEN ar.yoy_result = 'OVER_LIMIT'
     AND MAX(p.historical_daily_energy_yoy) IS NOT NULL
     AND CAST(JSON_UNQUOTE(JSON_EXTRACT(yoy_ref_benchmark.values_json, '$."1"')) AS DECIMAL(18,6)) > 0
    THEN ROUND(
      (
        ar.current_daily_avg_kwh -
        (
          ar.yoy_reference_daily_kwh_c
          * GREATEST(
              1,
              ar.yoy_current_benchmark_avg_a
              / CAST(JSON_UNQUOTE(JSON_EXTRACT(yoy_ref_benchmark.values_json, '$."1"')) AS DECIMAL(18,6))
            )
          * 1.2
        )
      )
      /
      (
        ar.yoy_reference_daily_kwh_c
        * GREATEST(
            1,
            ar.yoy_current_benchmark_avg_a
            / CAST(JSON_UNQUOTE(JSON_EXTRACT(yoy_ref_benchmark.values_json, '$."1"')) AS DECIMAL(18,6))
          )
        * 1.2
      ) * 100,
      6
    )
    ELSE NULL
  END AS yoy_candidate_ratio_reference_first_day,
  ROUND(ar.yoy_factor_k, 6) AS system_yoy_k,
  ar.mom_result,
  ROUND(ar.mom_ratio, 6) AS system_mom_ratio,
  ROUND(ar.mom_threshold_daily_kwh, 6) AS system_mom_threshold,
  ar.mom_reference_period,
  ROUND(ar.mom_reference_daily_kwh_c, 6) AS system_mom_c,
  ROUND(ar.mom_current_benchmark_avg_a, 6) AS system_mom_a,
  ROUND(ar.mom_reference_benchmark_avg_b, 6) AS system_mom_b,
  ROUND(CAST(JSON_UNQUOTE(JSON_EXTRACT(mom_ref_benchmark.values_json, '$."1"')) AS DECIMAL(18,6)), 6) AS mom_reference_benchmark_first_day,
  CASE
    WHEN ar.mom_result = 'OVER_LIMIT'
     AND MAX(p.historical_daily_energy_mom) IS NOT NULL
     AND CAST(JSON_UNQUOTE(JSON_EXTRACT(mom_ref_benchmark.values_json, '$."1"')) AS DECIMAL(18,6)) > 0
    THEN ROUND(
      (
        ar.current_daily_avg_kwh -
        (
          ar.mom_reference_daily_kwh_c
          * GREATEST(
              1,
              ar.mom_current_benchmark_avg_a
              / CAST(JSON_UNQUOTE(JSON_EXTRACT(mom_ref_benchmark.values_json, '$."1"')) AS DECIMAL(18,6))
            )
          * 1.2
        )
      )
      /
      (
        ar.mom_reference_daily_kwh_c
        * GREATEST(
            1,
            ar.mom_current_benchmark_avg_a
            / CAST(JSON_UNQUOTE(JSON_EXTRACT(mom_ref_benchmark.values_json, '$."1"')) AS DECIMAL(18,6))
          )
        * 1.2
      ) * 100,
      6
    )
    ELSE NULL
  END AS mom_candidate_ratio_reference_first_day,
  ROUND(ar.mom_factor_k, 6) AS system_mom_k,
  ar.rated_result,
  ROUND(ar.rated_ratio, 6) AS system_rated_ratio,
  ROUND(ar.rated_total_kwh, 6) AS system_rated_threshold,
  CASE WHEN MAX(p.historical_daily_energy_yoy) IS NULL THEN 'NORMAL' ELSE 'OVER_LIMIT' END AS external_yoy_result,
  CASE WHEN MAX(p.historical_daily_energy_mom) IS NULL THEN 'NORMAL' ELSE 'OVER_LIMIT' END AS external_mom_result,
  CASE WHEN MAX(p.rated_power_benchmark) IS NULL THEN 'NORMAL' ELSE 'OVER_LIMIT' END AS external_rated_result,
  CASE
    WHEN MAX(p.historical_daily_energy_yoy) IS NULL AND COALESCE(ar.yoy_result, 'NA') <> 'OVER_LIMIT' THEN 'MATCH'
    WHEN MAX(p.historical_daily_energy_yoy) IS NOT NULL
         AND ar.yoy_result = 'OVER_LIMIT'
         AND ABS(MAX(p.historical_daily_energy_yoy) - COALESCE(ar.yoy_ratio, -999999)) <= $TolerancePercent THEN 'MATCH'
    ELSE 'MISMATCH'
  END AS yoy_compare,
  ROUND(ABS(COALESCE(MAX(p.historical_daily_energy_yoy), 0) - COALESCE(ar.yoy_ratio, 0)), 6) AS yoy_ratio_diff,
  CASE
    WHEN MAX(p.historical_daily_energy_mom) IS NULL AND COALESCE(ar.mom_result, 'NA') <> 'OVER_LIMIT' THEN 'MATCH'
    WHEN MAX(p.historical_daily_energy_mom) IS NOT NULL
         AND ar.mom_result = 'OVER_LIMIT'
         AND ABS(MAX(p.historical_daily_energy_mom) - COALESCE(ar.mom_ratio, -999999)) <= $TolerancePercent THEN 'MATCH'
    ELSE 'MISMATCH'
  END AS mom_compare,
  ROUND(ABS(COALESCE(MAX(p.historical_daily_energy_mom), 0) - COALESCE(ar.mom_ratio, 0)), 6) AS mom_ratio_diff,
  CASE
    WHEN MAX(p.rated_power_benchmark) IS NULL AND COALESCE(ar.rated_result, 'NA') <> 'OVER_LIMIT' THEN 'MATCH'
    WHEN MAX(p.rated_power_benchmark) IS NOT NULL
         AND ar.rated_result = 'OVER_LIMIT'
         AND ABS(MAX(p.rated_power_benchmark) - COALESCE(ar.rated_ratio, -999999)) <= $TolerancePercent THEN 'MATCH'
    ELSE 'MISMATCH'
  END AS rated_compare,
  ROUND(ABS(COALESCE(MAX(p.rated_power_benchmark), 0) - COALESCE(ar.rated_ratio, 0)), 6) AS rated_ratio_diff,
  CASE
    WHEN ar.public_id IS NULL THEN '系统未生成稽核结果'
    WHEN MAX(p.historical_daily_energy_yoy) IS NOT NULL AND COALESCE(ar.yoy_result, 'NA') <> 'OVER_LIMIT' THEN '同比外部为超标但系统未超标，优先检查参考账期、审核通过和标杆 A/B'
    WHEN MAX(p.historical_daily_energy_yoy) IS NULL AND COALESCE(ar.yoy_result, 'NA') = 'OVER_LIMIT' THEN '同比外部为正常但系统超标，优先检查公式和参考账期'
    WHEN MAX(p.historical_daily_energy_yoy) IS NOT NULL AND ABS(MAX(p.historical_daily_energy_yoy) - COALESCE(ar.yoy_ratio, -999999)) > $TolerancePercent THEN '同比比例偏差，优先检查日均取值、缴费天数、标杆 A/B 和四舍五入'
    WHEN MAX(p.historical_daily_energy_mom) IS NOT NULL AND COALESCE(ar.mom_result, 'NA') <> 'OVER_LIMIT' THEN '环比外部为超标但系统未超标，优先检查审核通过参考账期'
    WHEN MAX(p.historical_daily_energy_mom) IS NULL AND COALESCE(ar.mom_result, 'NA') = 'OVER_LIMIT' THEN '环比外部为正常但系统超标，优先检查审核通过参考账期'
    WHEN MAX(p.historical_daily_energy_mom) IS NOT NULL AND ABS(MAX(p.historical_daily_energy_mom) - COALESCE(ar.mom_ratio, -999999)) > $TolerancePercent THEN '环比比例偏差，优先检查日均取值、参考账期、标杆 A/B 和四舍五入'
    WHEN MAX(p.rated_power_benchmark) IS NOT NULL AND COALESCE(ar.rated_result, 'NA') <> 'OVER_LIMIT' THEN '额定外部为超标但系统未超标，优先检查额定标杆总量'
    WHEN MAX(p.rated_power_benchmark) IS NULL AND COALESCE(ar.rated_result, 'NA') = 'OVER_LIMIT' THEN '额定外部为正常但系统超标，优先检查额定标杆总量'
    WHEN MAX(p.rated_power_benchmark) IS NOT NULL AND ABS(MAX(p.rated_power_benchmark) - COALESCE(ar.rated_ratio, -999999)) > $TolerancePercent THEN '额定比例偏差，优先检查 calculated_day_total / benchmark_month_value / day_total 优先级'
    ELSE '一致'
  END AS difference_reason_candidate
FROM payment_detail p
LEFT JOIN audit_result ar
  ON ar.city_code = p.city_code
 AND ar.billing_point_code = p.billing_point_code
 AND ar.data_period = p.data_period
LEFT JOIN benchmark_value yoy_ref_benchmark
  ON yoy_ref_benchmark.city_code = ar.city_code
 AND yoy_ref_benchmark.billing_point_code = ar.billing_point_code
 AND yoy_ref_benchmark.data_period = ar.yoy_reference_period
LEFT JOIN benchmark_value mom_ref_benchmark
  ON mom_ref_benchmark.city_code = ar.city_code
 AND mom_ref_benchmark.billing_point_code = ar.billing_point_code
 AND mom_ref_benchmark.data_period = ar.mom_reference_period
WHERE p.data_period >= '$SincePeriod'
GROUP BY p.billing_point_name, p.billing_point_code, p.city_code, p.data_period,
         ar.public_id, ar.audit_status, ar.over_limit_type, ar.current_daily_avg_kwh,
         ar.yoy_result, ar.yoy_ratio, ar.yoy_threshold_daily_kwh, ar.yoy_reference_period,
         ar.yoy_reference_daily_kwh_c, ar.yoy_current_benchmark_avg_a,
         ar.yoy_reference_benchmark_avg_b, yoy_ref_benchmark.values_json, ar.yoy_factor_k,
         ar.mom_result, ar.mom_ratio, ar.mom_threshold_daily_kwh, ar.mom_reference_period,
         ar.mom_reference_daily_kwh_c, ar.mom_current_benchmark_avg_a,
         ar.mom_reference_benchmark_avg_b, mom_ref_benchmark.values_json, ar.mom_factor_k,
         ar.rated_result, ar.rated_ratio, ar.rated_total_kwh
ORDER BY p.billing_point_name, p.data_period
"@

$headers = @(
  "billing_point_name", "billing_point_code", "city_code", "period",
  "payment_total_kwh", "payment_amount", "payment_days",
  "external_yoy_ratio", "external_mom_ratio", "external_rated_ratio",
  "system_audit_status", "system_over_limit_type", "system_current_daily",
  "system_yoy_result", "system_yoy_ratio", "system_yoy_threshold", "system_yoy_reference_period",
  "system_yoy_c", "system_yoy_a", "system_yoy_b",
  "yoy_reference_benchmark_first_day", "yoy_candidate_ratio_reference_first_day",
  "system_yoy_k",
  "system_mom_result", "system_mom_ratio", "system_mom_threshold", "system_mom_reference_period",
  "system_mom_c", "system_mom_a", "system_mom_b",
  "mom_reference_benchmark_first_day", "mom_candidate_ratio_reference_first_day",
  "system_mom_k",
  "system_rated_result", "system_rated_ratio", "system_rated_threshold",
  "external_yoy_result", "external_mom_result", "external_rated_result",
  "yoy_compare", "yoy_ratio_diff", "mom_compare", "mom_ratio_diff",
  "rated_compare", "rated_ratio_diff", "difference_reason_candidate"
)

$rawLines = & mysql --host=$HostName --user=$User --password=$Password --default-character-set=utf8mb4 --batch --raw --skip-column-names --execute=$sql $Database
if ($LASTEXITCODE -ne 0) {
  throw "MySQL query failed."
}

$rows = foreach ($line in $rawLines) {
  if ([string]::IsNullOrWhiteSpace($line)) {
    continue
  }
  $cells = $line.Split([char]9)
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

Write-Host "稽核对账报告已导出：$OutputPath"
Write-Host "对账账期记录数：$($rows.Count)；差异记录数：$($mismatches.Count)"
if ($mismatches.Count -gt 0) {
  $mismatches |
    Select-Object billing_point_name, period, yoy_compare, yoy_ratio_diff, mom_compare, mom_ratio_diff, rated_compare, rated_ratio_diff, difference_reason_candidate |
    Format-Table -AutoSize
}
