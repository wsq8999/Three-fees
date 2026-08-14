package com.threefees.importing.application;

import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.FieldDefinition;
import com.threefees.importing.domain.ImportError;
import com.threefees.organization.application.CityQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ImportRowMapper {

  private static final int MAX_REPORTED_ERRORS = 1000;

  private static final String BILLING_POINT_CODE = "\u62a5\u8d26\u70b9\u7f16\u7801";
  private static final String BILLING_POINT_NAME = "\u62a5\u8d26\u70b9\u540d\u79f0";
  private static final String CITY = "\u6240\u5c5e\u5730\u5e02";
  private static final String BENCHMARK_CITY = "\u5730\u5e02";
  private static final String PAYMENT_CODE = "\u7f34\u8d39\u5355\u7f16\u7801";
  private static final String PERIOD_START = "\u7f34\u8d39\u671f\u59cb";
  private static final String PERIOD_END = "\u7f34\u8d39\u671f\u7ec8";
  private static final String LAST_PERIOD_START = "\u6700\u540e\u62a5\u8d26\u671f\u59cb";
  private static final String LAST_PERIOD_END = "\u6700\u540e\u62a5\u8d26\u671f\u7ec8";
  private static final String AUDIT_STATUS = "\u5ba1\u6838\u72b6\u6001";
  private static final String ACTUAL_AMOUNT = "\u5b9e\u9645\u62a5\u8d26\u91d1\u989d";
  private static final String METER_CODE = "\u7535\u8868\u7f16\u7801";
  private static final String ALLOCATED_ENERGY = "\u5206\u644a\u540e\u5ea6\u6570";
  private static final String YEAR = "\u5e74\u4efd";
  private static final String MONTH = "\u6708\u4efd";
  private static final String MONTHLY_BENCHMARK = "\u6708\u603b\u6807\u6746";
  private static final String MONTHLY_AVERAGE_BENCHMARK = "\u6708\u5e73\u5747\u6807\u6746";

  private final FieldCatalogService fieldCatalogService;
  private final CityQueryService cityQueryService;
  private final ImportBatchRepository batchRepository;
  private final ObjectMapper objectMapper;

  public ImportRowMapper(
      FieldCatalogService fieldCatalogService,
      CityQueryService cityQueryService,
      ImportBatchRepository batchRepository,
      ObjectMapper objectMapper) {
    this.fieldCatalogService = fieldCatalogService;
    this.cityQueryService = cityQueryService;
    this.batchRepository = batchRepository;
    this.objectMapper = objectMapper;
  }

  public List<ImportRow> map(
      DatasetType datasetType, String period, String expectedCityCode, TabularData data) {
    return mapAuto(datasetType, expectedCityCode, period, data).stream()
        .filter(group -> group.cityCode().equals(expectedCityCode) && group.period().equals(period))
        .findFirst()
        .map(ImportRowGroup::rows)
        .orElseThrow(
            () ->
                new ImportValidationException(
                    List.of(
                        new ImportError(
                            0,
                            "datasetType",
                            "IMPORT_SCOPE_MISMATCH",
                            "\u6587\u4ef6\u57ce\u5e02\u6216\u8d26\u671f\u4e0e\u5bfc\u5165\u6279\u6b21\u4e0d\u4e00\u81f4"))));
  }

  public List<ImportRowGroup> mapAuto(
      DatasetType datasetType, String expectedCityCode, String fallbackPeriod, TabularData data) {
    List<FieldDefinition> fields = fieldCatalogService.fields(datasetType);
    var errors = new ArrayList<ImportError>();
    validateHeaders(fields, data.headers(), errors);
    if (!errors.isEmpty()) {
      throw new ImportValidationException(errors);
    }

    Map<String, String> cityCodes = cityCodes();
    var keys = new HashSet<String>();
    var groupedRows = new LinkedHashMap<GroupKey, List<ImportRow>>();
    for (int index = 0; index < data.rows().size(); index++) {
      int sourceRow = index + 2;
      List<String> raw = data.rows().get(index);
      if (raw.size() > fields.size()) {
        addError(
            errors,
            sourceRow,
            "*",
            "COLUMN_COUNT_MISMATCH",
            "\u6570\u636e\u5217\u6570\u5fc5\u987b\u4e3a " + fields.size());
        continue;
      }
      var values = new LinkedHashMap<String, String>();
      for (int column = 0; column < fields.size(); column++) {
        values.put(
            fields.get(column).technicalName(),
            column < raw.size() ? raw.get(column).trim() : "");
      }
      GroupedImportedRow row =
          validateAndBuild(
              datasetType, fallbackPeriod, expectedCityCode, sourceRow, values, cityCodes, errors);
      if (row != null && !keys.add(row.groupKey() + "|" + row.row().businessKey())) {
        addError(
            errors,
            sourceRow,
            "\u4e1a\u52a1\u552f\u4e00\u952e",
            "DUPLICATE_BUSINESS_KEY",
            "\u6587\u4ef6\u5185\u5b58\u5728\u91cd\u590d\u4e1a\u52a1\u8bb0\u5f55");
      } else if (row != null) {
        groupedRows.computeIfAbsent(row.groupKey(), ignored -> new ArrayList<>()).add(row.row());
      }
    }
    if (!errors.isEmpty()) {
      throw new ImportValidationException(errors);
    }
    return groupedRows.entrySet().stream()
        .sorted(
            Map.Entry.comparingByKey(
                Comparator.comparing(GroupKey::cityCode).thenComparing(GroupKey::period)))
        .map(
            entry ->
                new ImportRowGroup(
                    datasetType, entry.getKey().cityCode(), entry.getKey().period(), entry.getValue()))
        .toList();
  }

  private GroupedImportedRow validateAndBuild(
      DatasetType type,
      String fallbackPeriod,
      String expectedCityCode,
      int sourceRow,
      Map<String, String> values,
      Map<String, String> cityCodes,
      List<ImportError> errors) {
    String billingPointCode = required(values, BILLING_POINT_CODE, sourceRow, errors);
    String billingPointName = value(values, BILLING_POINT_NAME);
    if (billingPointName.isBlank() && type != DatasetType.METER_READING) {
      addError(errors, sourceRow, BILLING_POINT_NAME, "REQUIRED", "\u7f3a\u5c11\u5fc5\u586b\u5b57\u6bb5\uff1a" + BILLING_POINT_NAME);
    }

    String rowCity = null;
    String paymentCode = null;
    String meterCode = null;
    String businessKey;
    String period = fallbackPeriod;

    switch (type) {
      case BILLING_POINT -> {
        rowCity = resolveCity(value(values, CITY), cityCodes);
        period = fallbackPeriod;
        if (period == null || period.isBlank()) {
          period = inferPeriodFromDate(value(values, LAST_PERIOD_START), sourceRow, LAST_PERIOD_START, errors);
        }
        validateDateRange(value(values, LAST_PERIOD_START), value(values, LAST_PERIOD_END), sourceRow, errors);
        businessKey = billingPointCode + "|" + period + "|" + sourceRow;
      }
      case PAYMENT -> {
        rowCity = resolveCity(value(values, CITY), cityCodes);
        paymentCode = required(values, PAYMENT_CODE, sourceRow, errors);
        String start = required(values, PERIOD_START, sourceRow, errors);
        String end = required(values, PERIOD_END, sourceRow, errors);
        period = inferPeriodFromDate(start, sourceRow, PERIOD_START, errors);
        validateDateRange(start, end, sourceRow, errors);
        validateAuditStatus(required(values, AUDIT_STATUS, sourceRow, errors), sourceRow, errors);
        BigDecimal amount =
            decimal(required(values, ACTUAL_AMOUNT, sourceRow, errors), sourceRow, ACTUAL_AMOUNT, errors);
        if (amount != null && amount.signum() < 0) {
          addError(
              errors,
              sourceRow,
              ACTUAL_AMOUNT,
              "NEGATIVE_VALUE",
              "\u5b9e\u9645\u62a5\u8d26\u91d1\u989d\u4e0d\u80fd\u4e3a\u8d1f\u6570");
        }
        businessKey = billingPointCode + "|" + paymentCode + "|" + period + "|" + start + "|" + end;
      }
      case METER_READING -> {
        paymentCode = required(values, PAYMENT_CODE, sourceRow, errors);
        meterCode = required(values, METER_CODE, sourceRow, errors);
        String start = required(values, PERIOD_START, sourceRow, errors);
        String end = required(values, PERIOD_END, sourceRow, errors);
        period = inferPeriodFromDate(start, sourceRow, PERIOD_START, errors);
        validateDateRange(start, end, sourceRow, errors);
        rowCity = inferMeterCity(expectedCityCode, period, billingPointCode, paymentCode);
        if (rowCity == null) {
          addError(
              errors,
              sourceRow,
              CITY,
              "CITY_UNKNOWN",
              "\u7535\u8868\u8bfb\u6570\u65e0\u6cd5\u6839\u636e\u7f34\u8d39\u5355\u7f16\u7801\u6216\u62a5\u8d26\u70b9\u7f16\u7801\u63a8\u65ad\u57ce\u5e02");
        }
        BigDecimal allocated =
            decimal(required(values, ALLOCATED_ENERGY, sourceRow, errors), sourceRow, ALLOCATED_ENERGY, errors);
        if (allocated != null && allocated.signum() < 0) {
          addError(
              errors,
              sourceRow,
              ALLOCATED_ENERGY,
              "NEGATIVE_VALUE",
              "\u5206\u644a\u540e\u5ea6\u6570\u4e0d\u80fd\u4e3a\u8d1f\u6570");
        }
        businessKey = paymentCode + "|" + meterCode + "|" + start + "|" + sourceRow;
      }
      case BENCHMARK -> {
        rowCity = resolveCity(value(values, BENCHMARK_CITY), cityCodes);
        period = inferBenchmarkPeriod(sourceRow, values, errors);
        validateBenchmark(period, sourceRow, values, errors);
        businessKey = billingPointCode + "|" + period;
      }
      default -> throw new IllegalStateException("Unsupported dataset type: " + type);
    }

    if (rowCity == null && type != DatasetType.METER_READING) {
      addError(
          errors,
          sourceRow,
          type == DatasetType.BENCHMARK ? BENCHMARK_CITY : CITY,
          "CITY_UNKNOWN",
          "\u57ce\u5e02\u65e0\u6cd5\u8bc6\u522b");
    } else if (rowCity != null
        && expectedCityCode != null
        && !expectedCityCode.isBlank()
        && !rowCity.equals(expectedCityCode)) {
      addError(
          errors,
          sourceRow,
          CITY,
          "CITY_SCOPE_MISMATCH",
          "\u6587\u4ef6\u5305\u542b\u5176\u4ed6\u57ce\u5e02\u6570\u636e");
    }

    if (billingPointCode.isBlank() || rowCity == null || period == null || period.isBlank()) {
      return null;
    }
    return new GroupedImportedRow(
        new GroupKey(rowCity, period),
        new ImportRow(
            sourceRow,
            rowCity,
            billingPointCode,
            billingPointName,
            blankToNull(paymentCode),
            blankToNull(meterCode),
            businessKey,
            writeJson(values)));
  }

  private String inferMeterCity(
      String expectedCityCode, String period, String billingPointCode, String paymentCode) {
    if (expectedCityCode != null && !expectedCityCode.isBlank()) {
      return expectedCityCode;
    }
    if (period == null || period.isBlank()) {
      return null;
    }
    return batchRepository
        .findActiveCityForPayment(period, billingPointCode, paymentCode)
        .or(() -> batchRepository.findActiveCityForBillingPoint(period, billingPointCode))
        .orElse(null);
  }

  private void validateDateRange(
      String startRaw, String endRaw, int sourceRow, List<ImportError> errors) {
    LocalDate start = parseDate(startRaw);
    LocalDate end = parseDate(endRaw);
    if (start == null || end == null) {
      return;
    }
    if (start.isAfter(end)) {
      addError(
          errors,
          sourceRow,
          PERIOD_END,
          "DATE_RANGE_INVALID",
          "\u7f34\u8d39\u671f\u59cb\u665a\u4e8e\u7f34\u8d39\u671f\u7ec8");
    }
  }

  private String inferPeriodFromDate(
      String raw, int sourceRow, String column, List<ImportError> errors) {
    LocalDate date = parseDate(raw);
    if (date == null) {
      addError(
          errors,
          sourceRow,
          column,
          "DATE_INVALID",
          "\u65e5\u671f\u683c\u5f0f\u4e0d\u6b63\u786e\uff1a" + raw);
      return "";
    }
    return YearMonth.from(date).toString();
  }

  private String inferBenchmarkPeriod(
      int sourceRow, Map<String, String> values, List<ImportError> errors) {
    try {
      int year = Integer.parseInt(value(values, YEAR));
      int month = Integer.parseInt(value(values, MONTH).replaceFirst("^0", ""));
      return YearMonth.of(year, month).toString();
    } catch (RuntimeException exception) {
      addError(
          errors,
          sourceRow,
          YEAR + "/" + MONTH,
          "PERIOD_INVALID",
          "\u5e74\u4efd\u6708\u4efd\u4e0d\u6b63\u786e");
      return "";
    }
  }

  private LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    for (DateTimeFormatter formatter :
        List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"))) {
      try {
        return LocalDate.parse(raw, formatter);
      } catch (DateTimeParseException ignored) {
        // Try next supported source format.
      }
    }
    return null;
  }

  private void validateAuditStatus(String status, int sourceRow, List<ImportError> errors) {
    // The source systems have used several localized workflow labels over time.  The
    // import contract only requires the field to be present; the raw value is preserved
    // and later aggregation decides whether it means approved/pending.
  }

  private void validateBenchmark(
      String period, int sourceRow, Map<String, String> values, List<ImportError> errors) {
    if (period == null || period.isBlank()) {
      return;
    }
    YearMonth month = YearMonth.parse(period);
    decimal(required(values, MONTHLY_BENCHMARK, sourceRow, errors), sourceRow, MONTHLY_BENCHMARK, errors);
    for (int day = 1; day <= 31; day++) {
      String column = Integer.toString(day);
      String raw = value(values, column);
      if (day <= month.lengthOfMonth()) {
        BigDecimal dayValue = decimal(raw, sourceRow, column, errors);
        if (dayValue != null) {
          if (dayValue.signum() < 0) {
            addError(
                errors,
                sourceRow,
                column,
                "NEGATIVE_VALUE",
                "\u65e5\u6807\u6746\u503c\u4e0d\u80fd\u4e3a\u8d1f\u6570");
          }
        }
      }
    }
  }

  private BigDecimal decimal(
      String raw, int sourceRow, String column, List<ImportError> errors) {
    String normalized = raw == null ? "" : raw.trim().replace(",", "").replace("%", "");
    if (normalized.isBlank()
        || "-".equals(normalized)
        || "\u2014".equals(normalized)
        || "\u2013".equals(normalized)) {
      return null;
    }
    try {
      return new BigDecimal(normalized);
    } catch (RuntimeException exception) {
      addError(
          errors,
          sourceRow,
          column,
          "DECIMAL_INVALID",
          "\u6570\u503c\u683c\u5f0f\u4e0d\u6b63\u786e\uff1a" + raw);
      return null;
    }
  }

  private String required(
      Map<String, String> values, String column, int sourceRow, List<ImportError> errors) {
    String raw = value(values, column);
    if (raw.isBlank()) {
      addError(errors, sourceRow, column, "REQUIRED", "\u7f3a\u5c11\u5fc5\u586b\u5b57\u6bb5\uff1a" + column);
    }
    return raw;
  }

  private void validateHeaders(
      List<FieldDefinition> fields, List<String> headers, List<ImportError> errors) {
    if (headers.size() != fields.size()) {
      addError(
          errors,
          1,
          "*",
          "HEADER_COUNT_MISMATCH",
          "\u8868\u5934\u6570\u91cf\u4e0d\u5339\u914d\uff0c\u671f\u671b "
              + fields.size()
              + " \u5217\uff0c\u5b9e\u9645 "
              + headers.size()
              + " \u5217");
      return;
    }
    for (int index = 0; index < fields.size(); index++) {
      String expected = fields.get(index).sourceName();
      String actual = headers.get(index).trim();
      if (!headerMatches(expected, actual)) {
        addError(
            errors,
            1,
            expected,
            "HEADER_MISMATCH",
            "\u8868\u5934\u4e0d\u5339\u914d\uff0c\u671f\u671b\uff1a" + expected + "\uff0c\u5b9e\u9645\uff1a" + actual);
      }
    }
  }

  private boolean headerMatches(String expected, String actual) {
    if (expected.equals(actual)) {
      return true;
    }
    if (expected.equals("\u8ba1\u8d39\u65b9\u5f0f") && actual.equals("\u8ba1\u8d39\u65b9\u5f0f.1")) {
      return true;
    }
    return expected.equals(MONTHLY_BENCHMARK) && actual.equals(MONTHLY_AVERAGE_BENCHMARK);
  }

  private Map<String, String> cityCodes() {
    var map = new LinkedHashMap<String, String>();
    cityQueryService
        .findAll()
        .forEach(
            city -> {
              map.put(city.code(), city.code());
              map.put(city.name(), city.code());
              if (city.name().endsWith("\u5e02")) {
                map.put(city.name().substring(0, city.name().length() - 1), city.code());
              }
            });
    return map;
  }

  private String resolveCity(String raw, Map<String, String> cityCodes) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim();
    String city = cityCodes.get(normalized);
    if (city != null) {
      return city;
    }
    if (!normalized.endsWith("\u5e02")) {
      city = cityCodes.get(normalized + "\u5e02");
    }
    return city;
  }

  private String value(Map<String, String> values, String column) {
    return values.getOrDefault(column, "").trim();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private void addError(
      List<ImportError> errors, int row, String column, String code, String message) {
    if (errors.size() < MAX_REPORTED_ERRORS) {
      errors.add(new ImportError(row, column, code, message));
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Import row could not be serialized", exception);
    }
  }

  private record GroupKey(String cityCode, String period) {}

  private record GroupedImportedRow(GroupKey groupKey, ImportRow row) {}
}


