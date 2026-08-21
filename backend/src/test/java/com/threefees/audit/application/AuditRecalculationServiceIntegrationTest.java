package com.threefees.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ThreeFeesApplication;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
    classes = ThreeFeesApplication.class,
    properties = {
      "app.bootstrap.enabled=true",
      "app.bootstrap.initial-password=test-password-123456"
    })
class AuditRecalculationServiceIntegrationTest {

  private static final String CITY_CODE = "320100";

  @Autowired private AuditRecalculationService service;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTestData() {
    jdbcTemplate.update("DELETE FROM benchmark_value WHERE billing_point_code LIKE 'BP-BENCHMARK-%'");
  }

  @Test
  void benchmarkTotalPrefersCalculatedThenMonthValueThenDayTotal() {
    insertBenchmark("BP-BENCHMARK-CALCULATED", "2026-01", "100", "200", "300");
    insertBenchmark("BP-BENCHMARK-ZERO", "2026-01", "100", "200", "0");
    insertBenchmark("BP-BENCHMARK-MONTH", "2026-01", "100", "200", null);
    insertBenchmark("BP-BENCHMARK-DAY", "2026-01", "100", null, null);

    assertThat(loadBenchmarkTotal("BP-BENCHMARK-CALCULATED")).isEqualByComparingTo("300");
    assertThat(loadBenchmarkTotal("BP-BENCHMARK-ZERO")).isEqualByComparingTo("0");
    assertThat(loadBenchmarkTotal("BP-BENCHMARK-MONTH")).isEqualByComparingTo("200");
    assertThat(loadBenchmarkTotal("BP-BENCHMARK-DAY")).isEqualByComparingTo("100");
  }

  private BigDecimal loadBenchmarkTotal(String billingPointCode) {
    return ReflectionTestUtils.invokeMethod(
        service, "loadBenchmarkTotal", YearMonth.of(2026, 1), CITY_CODE, billingPointCode);
  }

  private void insertBenchmark(
      String billingPointCode,
      String period,
      String dayTotal,
      String benchmarkMonthValue,
      String calculatedDayTotal) {
    jdbcTemplate.update(
        """
        INSERT INTO benchmark_value
          (public_id, data_period, period_start, period_end, city_code, source_import_job_id,
           source_row_no, raw_row_json, billing_point_code, billing_point_name, city_name,
           benchmark_year, benchmark_month, month_avg_benchmark, day_total, calculated_month_avg,
           validation_status, benchmark_month_value, calculated_day_total, values_json)
        VALUES (?, ?, ?, ?, ?, 0, 1, '{}', ?, ?, '南京市', 2026, 1, 0, ?, 0, 'VALID', ?, ?, '{}')
        """,
        UUID.randomUUID().toString(),
        period,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31),
        CITY_CODE,
        billingPointCode,
        billingPointCode,
        decimal(dayTotal),
        decimal(benchmarkMonthValue),
        decimal(calculatedDayTotal));
  }

  private BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }
}
