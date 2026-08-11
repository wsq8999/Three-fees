package com.threefees.importing.application;

import com.threefees.importing.application.ImportBatchRepository.ImportedRow;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.FieldDefinition;
import com.threefees.importing.domain.ImportError;
import com.threefees.organization.application.CityQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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

  private final FieldCatalogService fieldCatalogService;
  private final CityQueryService cityQueryService;
  private final ObjectMapper objectMapper;

  public ImportRowMapper(
      FieldCatalogService fieldCatalogService,
      CityQueryService cityQueryService,
      ObjectMapper objectMapper) {
    this.fieldCatalogService = fieldCatalogService;
    this.cityQueryService = cityQueryService;
    this.objectMapper = objectMapper;
  }

  public List<ImportedRow> map(
      DatasetType datasetType, String period, String expectedCityCode, TabularData data) {
    List<FieldDefinition> fields = fieldCatalogService.fields(datasetType);
    var errors = new ArrayList<ImportError>();
    validateHeaders(fields, data.headers(), errors);
    if (!errors.isEmpty()) {
      throw new ImportValidationException(errors);
    }

    Map<String, String> cityCodes = cityCodes();
    var keys = new HashSet<String>();
    var importedRows = new ArrayList<ImportedRow>();
    for (int index = 0; index < data.rows().size(); index++) {
      int sourceRow = index + 2;
      List<String> raw = data.rows().get(index);
      if (raw.size() != fields.size()) {
        addError(errors, sourceRow, "*", "COLUMN_COUNT_MISMATCH", "数据列数必须为 " + fields.size());
        continue;
      }
      var values = new LinkedHashMap<String, String>();
      for (int column = 0; column < fields.size(); column++) {
        values.put(fields.get(column).technicalName(), raw.get(column).trim());
      }
      validateSuggestedTypes(fields, values, sourceRow, errors);
      ImportedRow row =
          validateAndBuild(
              datasetType, period, expectedCityCode, sourceRow, values, cityCodes, errors);
      if (row != null && !keys.add(row.businessKey())) {
        addError(errors, sourceRow, "业务唯一键", "DUPLICATE_BUSINESS_KEY", "文件内存在重复业务记录");
      } else if (row != null) {
        importedRows.add(row);
      }
    }
    if (!errors.isEmpty()) {
      throw new ImportValidationException(errors);
    }
    return List.copyOf(importedRows);
  }

  private ImportedRow validateAndBuild(
      DatasetType type,
      String period,
      String expectedCityCode,
      int sourceRow,
      Map<String, String> values,
      Map<String, String> cityCodes,
      List<ImportError> errors) {
    String billingPointCode = required(values, "报账点编码", sourceRow, errors);
    String billingPointName = value(values, "报账点名称");
    if (billingPointName.isBlank() && type != DatasetType.METER_READING) {
      addError(errors, sourceRow, "报账点名称", "REQUIRED", "报账点名称不能为空");
    }
    String rowCity =
        type == DatasetType.METER_READING
            ? expectedCityCode
            : resolveCity(value(values, type == DatasetType.BENCHMARK ? "地市" : "所属地市"), cityCodes);
    if (rowCity == null) {
      addError(errors, sourceRow, "所属地市", "CITY_UNKNOWN", "所属地市无法识别");
    } else if (!rowCity.equals(expectedCityCode)) {
      addError(errors, sourceRow, "所属地市", "CITY_SCOPE_MISMATCH", "文件包含其他地市数据");
    }

    String paymentCode = null;
    String meterCode = null;
    String businessKey;
    switch (type) {
      case BILLING_POINT -> businessKey = billingPointCode + "|" + period;
      case PAYMENT -> {
        paymentCode = required(values, "缴费单编码", sourceRow, errors);
        String start = required(values, "缴费期始", sourceRow, errors);
        String end = required(values, "缴费期终", sourceRow, errors);
        validateNaturalMonthRange(period, start, end, sourceRow, errors);
        String auditStatus = required(values, "审核状态", sourceRow, errors);
        validateAuditStatus(auditStatus, sourceRow, errors);
        BigDecimal amount =
            decimal(
                required(values, "实际报账金额", sourceRow, errors), sourceRow, "实际报账金额", errors, true);
        if (amount != null && amount.signum() < 0) {
          addError(errors, sourceRow, "实际报账金额", "NEGATIVE_VALUE", "实际报账金额不能为负数");
        }
        businessKey = billingPointCode + "|" + paymentCode + "|" + period + "|" + start + "|" + end;
      }
      case METER_READING -> {
        paymentCode = required(values, "缴费单编码", sourceRow, errors);
        meterCode = required(values, "电表编码", sourceRow, errors);
        String start = required(values, "缴费期始", sourceRow, errors);
        String end = required(values, "缴费期终", sourceRow, errors);
        validateNaturalMonthRange(period, start, end, sourceRow, errors);
        BigDecimal allocated =
            decimal(required(values, "分摊后度数", sourceRow, errors), sourceRow, "分摊后度数", errors, true);
        if (allocated != null && allocated.signum() < 0) {
          addError(errors, sourceRow, "分摊后度数", "NEGATIVE_VALUE", "分摊后度数不能为负数");
        }
        businessKey = paymentCode + "|" + meterCode + "|" + start;
      }
      case BENCHMARK -> {
        validateBenchmark(period, sourceRow, values, errors);
        businessKey = billingPointCode + "|" + period;
      }
      default -> throw new IllegalStateException("Unsupported dataset type: " + type);
    }
    if (billingPointCode.isBlank() || rowCity == null) {
      return null;
    }
    return new ImportedRow(
        sourceRow,
        rowCity,
        billingPointCode,
        billingPointName,
        blankToNull(paymentCode),
        blankToNull(meterCode),
        businessKey,
        writeJson(values));
  }

  private void validateSuggestedTypes(
      List<FieldDefinition> fields,
      Map<String, String> values,
      int sourceRow,
      List<ImportError> errors) {
    for (FieldDefinition field : fields) {
      String raw = value(values, field.technicalName());
      if (raw.isBlank()) {
        continue;
      }
      String type = field.suggestedType().toLowerCase(java.util.Locale.ROOT);
      if (type.startsWith("decimal")) {
        decimal(raw, sourceRow, field.sourceName(), errors, false);
      } else if (type.equals("integer")) {
        try {
          Integer.parseInt(raw.replace(",", ""));
        } catch (NumberFormatException exception) {
          addError(errors, sourceRow, field.sourceName(), "INTEGER_INVALID", "字段必须是整数");
        }
      } else if (type.equals("date")) {
        validateDate(raw, sourceRow, field.sourceName(), errors, false);
      } else if (type.equals("datetime")) {
        validateDate(raw, sourceRow, field.sourceName(), errors, true);
      } else if (type.equals("enum/boolean") && !isBooleanValue(raw)) {
        addError(
            errors, sourceRow, field.sourceName(), "BOOLEAN_INVALID", "布尔字段必须使用是/否、true/false、1/0");
      }
    }
  }

  private void validateDate(
      String raw, int sourceRow, String column, List<ImportError> errors, boolean allowTime) {
    List<DateTimeFormatter> dateFormats =
        List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"));
    for (DateTimeFormatter formatter : dateFormats) {
      try {
        LocalDate.parse(raw, formatter);
        return;
      } catch (DateTimeParseException ignored) {
        // Try the next accepted source format.
      }
    }
    if (allowTime) {
      try {
        LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return;
      } catch (DateTimeParseException ignored) {
        // Report one stable validation error below.
      }
    }
    addError(errors, sourceRow, column, "DATE_INVALID", "日期格式必须是可识别的自然日期");
  }

  private void validateNaturalMonthRange(
      String period, String startRaw, String endRaw, int sourceRow, List<ImportError> errors) {
    LocalDate start = parseDate(startRaw);
    LocalDate end = parseDate(endRaw);
    if (start == null || end == null) {
      return;
    }
    if (start.isAfter(end)) {
      addError(errors, sourceRow, "缴费期终", "DATE_RANGE_INVALID", "缴费期始不能晚于缴费期终");
      return;
    }
    YearMonth expected = YearMonth.parse(period);
    if (!start.equals(expected.atDay(1))) {
      addError(errors, sourceRow, "缴费期始", "PERIOD_MISMATCH", "缴费期始必须是所选自然月第一天");
    }
    if (!end.equals(expected.atEndOfMonth())) {
      addError(errors, sourceRow, "缴费期终", "PERIOD_MISMATCH", "缴费期终必须是所选自然月最后一天");
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
        // Try the next supported source format.
      }
    }
    return null;
  }

  private boolean isBooleanValue(String raw) {
    return Set.of("是", "否", "true", "false", "TRUE", "FALSE", "1", "0", "有", "无").contains(raw);
  }

  private void validateAuditStatus(String status, int sourceRow, List<ImportError> errors) {
    if (status.isBlank()) {
      return;
    }
    String normalized = status.toUpperCase(java.util.Locale.ROOT);
    boolean recognized =
        normalized.equals("APPROVED")
            || normalized.equals("PENDING")
            || normalized.equals("REJECTED")
            || status.contains("通过")
            || status.contains("待审核")
            || status.contains("审核中")
            || status.contains("未审核")
            || status.contains("驳回");
    if (!recognized) {
      addError(errors, sourceRow, "审核状态", "AUDIT_STATUS_INVALID", "审核状态不在允许范围内");
    }
  }

  private void validateBenchmark(
      String period, int sourceRow, Map<String, String> values, List<ImportError> errors) {
    YearMonth expected = YearMonth.parse(period);
    if (!Integer.toString(expected.getYear()).equals(value(values, "年份"))) {
      addError(errors, sourceRow, "年份", "PERIOD_MISMATCH", "年份与导入账期不一致");
    }
    String month = value(values, "月份").replaceFirst("^0", "");
    if (!Integer.toString(expected.getMonthValue()).equals(month)) {
      addError(errors, sourceRow, "月份", "PERIOD_MISMATCH", "月份与导入账期不一致");
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (int day = 1; day <= 31; day++) {
      String column = Integer.toString(day);
      String raw = value(values, column);
      if (day <= expected.lengthOfMonth()) {
        BigDecimal value = decimal(raw, sourceRow, column, errors, true);
        if (value != null) {
          if (value.signum() < 0) {
            addError(errors, sourceRow, column, "NEGATIVE_VALUE", "日标杆值不能为负数");
          } else {
            sum = sum.add(value);
          }
        }
      } else if (!raw.isBlank()) {
        addError(errors, sourceRow, column, "DAY_OUT_OF_RANGE", "当月不存在的日期列必须为空");
      }
    }
    BigDecimal importedTotal = decimal(value(values, "月总标杆"), sourceRow, "月总标杆", errors, true);
    if (importedTotal != null) {
      if (importedTotal.subtract(sum).abs().compareTo(new BigDecimal("0.01")) > 0) {
        addError(errors, sourceRow, "月总标杆", "MONTH_TOTAL_MISMATCH", "月总标杆与有效日值合计不一致");
      }
    }
  }

  private void validateHeaders(
      List<FieldDefinition> fields, List<String> headers, List<ImportError> errors) {
    if (headers.size() != fields.size()) {
      addError(errors, 1, "*", "HEADER_COLUMN_COUNT_MISMATCH", "表头列数必须为 " + fields.size());
      return;
    }
    for (int index = 0; index < fields.size(); index++) {
      if (!fields.get(index).sourceName().equals(headers.get(index).trim())) {
        addError(
            errors,
            1,
            Integer.toString(index + 1),
            "HEADER_MISMATCH",
            "第 " + (index + 1) + " 列应为“" + fields.get(index).sourceName() + "”");
      }
    }
  }

  private Map<String, String> cityCodes() {
    var result = new java.util.HashMap<String, String>();
    cityQueryService
        .findAll()
        .forEach(
            city -> {
              result.put(city.code(), city.code());
              result.put(city.name(), city.code());
              result.put(city.name().replace("市", ""), city.code());
            });
    return Map.copyOf(result);
  }

  private String resolveCity(String raw, Map<String, String> cityCodes) {
    return cityCodes.get(raw.trim());
  }

  private String required(
      Map<String, String> values, String field, int sourceRow, List<ImportError> errors) {
    String result = value(values, field);
    if (result.isBlank()) {
      addError(errors, sourceRow, field, "REQUIRED", field + "不能为空");
    }
    return result;
  }

  private String value(Map<String, String> values, String field) {
    return values.getOrDefault(field, "").trim();
  }

  private BigDecimal decimal(
      String raw, int sourceRow, String column, List<ImportError> errors, boolean required) {
    if (raw.isBlank()) {
      if (required) {
        addError(errors, sourceRow, column, "REQUIRED", column + "不能为空");
      }
      return null;
    }
    try {
      return new BigDecimal(raw.replace(",", ""));
    } catch (NumberFormatException exception) {
      addError(errors, sourceRow, column, "DECIMAL_INVALID", column + "必须是数字");
      return null;
    }
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Imported row could not be serialized", exception);
    }
  }

  private void addError(
      List<ImportError> errors, int row, String column, String code, String message) {
    if (errors.size() < MAX_REPORTED_ERRORS) {
      errors.add(new ImportError(row, column, code, message));
    }
  }
}
