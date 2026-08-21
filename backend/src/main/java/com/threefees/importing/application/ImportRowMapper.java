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
import java.util.HashMap;
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

  private static final String BILLING_POINT_CODE = "报账点编码";
  private static final String BILLING_POINT_NAME = "报账点名称";
  private static final String CITY = "所属地市";
  private static final String BENCHMARK_CITY = "地市";

  private static final String PAYMENT_CODE = "缴费单编码";
  private static final String PERIOD_START = "缴费期始";
  private static final String PERIOD_END = "缴费期终";

  private static final String LAST_PERIOD_START = "最后报账期始";
  private static final String LAST_PERIOD_END = "最后报账期终";

  private static final String AUDIT_STATUS = "审核状态";
  private static final String ACTUAL_AMOUNT = "实际报账金额";

  private static final String METER_CODE = "电表编码";
  private static final String ALLOCATED_ENERGY = "分摊后度数";

  private static final String YEAR = "年份";
  private static final String MONTH = "月份";

  private static final String MONTHLY_BENCHMARK = "月总标杆";
  private static final String MONTHLY_AVERAGE_BENCHMARK = "月平均标杆";

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
                            0, "datasetType", "IMPORT_SCOPE_MISMATCH", "文件城市或账期与导入批次不一致"))));
  }

  /**
   * 自动映射导入数据。
   *
   * <p>当前规则：
   *
   * <p>1. 不要求固定73/198/42/39列；
   *
   * <p>2. 不要求固定列顺序；
   *
   * <p>3. 根据表头名称找到数据；
   *
   * <p>4. 多出来的列自动忽略；
   *
   * <p>5. 缺少非必要字段时保存为空；
   *
   * <p>6. 必要业务字段仍然严格校验。
   */
  public List<ImportRowGroup> mapAuto(
      DatasetType datasetType, String expectedCityCode, String fallbackPeriod, TabularData data) {

    List<FieldDefinition> fields = fieldCatalogService.fields(datasetType);

    List<ImportError> errors = new ArrayList<>();

    /*
     * 先直接检查Excel真实表头中是否包含核心字段。
     *
     * 不依赖映射结果来检查表头，
     * 避免sourceName / technicalName转换导致误判。
     */
    validateRequiredHeaders(datasetType, fallbackPeriod, fields, data.headers(), errors);

    if (!errors.isEmpty()) {
      throw new ImportValidationException(errors);
    }

    /*
     * 建立：
     *
     * 系统technicalName -> Excel实际列位置
     */
    Map<String, Integer> columnIndexes = buildColumnIndexes(fields, data.headers());

    Map<String, String> cityCodes = cityCodes();

    Set<String> keys = new HashSet<>();

    Map<GroupKey, List<ImportRow>> groupedRows = new LinkedHashMap<>();

    for (int index = 0; index < data.rows().size(); index++) {

      int sourceRow = index + 2;

      List<String> raw = data.rows().get(index);

      Map<String, String> values = new LinkedHashMap<>();

      /*
       * 关键变化：
       *
       * 不再：
       * 第1列 -> 第1字段
       * 第2列 -> 第2字段
       *
       * 而是：
       * 根据表头名称找到真实列号。
       */
      for (FieldDefinition field : fields) {

        Integer column = columnIndexes.get(field.technicalName());

        String rawValue = "";

        if (column != null && column >= 0 && column < raw.size()) {

          String cell = raw.get(column);

          rawValue = cell == null ? "" : cell.trim();
        }

        values.put(field.technicalName(), rawValue);
      }

      GroupedImportedRow row =
          validateAndBuild(
              datasetType, fallbackPeriod, expectedCityCode, sourceRow, values, cityCodes, errors);

      if (row != null && !keys.add(row.groupKey() + "|" + row.row().businessKey())) {

        addError(errors, sourceRow, "业务唯一键", "DUPLICATE_BUSINESS_KEY", "文件内存在重复业务记录");

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
                    datasetType,
                    entry.getKey().cityCode(),
                    entry.getKey().period(),
                    entry.getValue()))
        .toList();
  }

  /**
   * 根据实际Excel表头建立字段映射。
   *
   * <p>支持列顺序任意调整。
   *
   * <p>例如：
   *
   * <p>A列原来是报账点编码， 后来移动到了D列， 仍然能找到D列。
   */
  private Map<String, Integer> buildColumnIndexes(
      List<FieldDefinition> fields, List<String> headers) {

    /*
     * 标准化表头名称 -> 所有出现位置。
     *
     * 保存List是为了支持重复表头，
     * 例如两个“计费方式”。
     */
    Map<String, List<Integer>> actualColumns = new LinkedHashMap<>();

    for (int index = 0; index < headers.size(); index++) {

      String normalized = normalizeHeader(headers.get(index));

      if (normalized.isBlank()) {
        continue;
      }

      actualColumns.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(index);
    }

    /*
     * 同名字段已经使用到第几个。
     */
    Map<String, Integer> usedOccurrences = new HashMap<>();

    Map<String, Integer> result = new LinkedHashMap<>();

    for (FieldDefinition field : fields) {

      List<String> candidates = candidateHeaderNames(field);

      String matchedName = null;
      List<Integer> positions = null;

      for (String candidate : candidates) {

        String normalizedCandidate = normalizeHeader(candidate);

        List<Integer> candidatePositions = actualColumns.get(normalizedCandidate);

        if (candidatePositions != null && !candidatePositions.isEmpty()) {

          matchedName = normalizedCandidate;

          positions = candidatePositions;

          break;
        }
      }

      /*
       * 上传文件没有这个字段。
       *
       * 如果它不是业务核心字段，
       * 允许缺失，后面值保持为空。
       */
      if (matchedName == null || positions == null || positions.isEmpty()) {

        continue;
      }

      int occurrence = usedOccurrences.getOrDefault(matchedName, 0);

      if (occurrence >= positions.size()) {

        continue;
      }

      int actualColumn = positions.get(occurrence);

      result.put(field.technicalName(), actualColumn);

      usedOccurrences.put(matchedName, occurrence + 1);
    }

    return result;
  }

  /** 一个系统字段允许使用的Excel表头名称。 */
  private List<String> candidateHeaderNames(FieldDefinition field) {

    List<String> candidates = new ArrayList<>();

    if (field.sourceName() != null && !field.sourceName().isBlank()) {

      candidates.add(field.sourceName());
    }

    if (field.technicalName() != null
        && !field.technicalName().isBlank()
        && !field.technicalName().equals(field.sourceName())) {

      candidates.add(field.technicalName());
    }

    /*
     * 原系统兼容：
     *
     * 月平均标杆 -> 月总标杆。
     */
    if (MONTHLY_BENCHMARK.equals(field.technicalName())) {

      candidates.add(MONTHLY_AVERAGE_BENCHMARK);
    }

    /*
     * 第二个计费方式的常见导出名称。
     */
    if ("计费方式__2".equals(field.technicalName())) {

      candidates.add("计费方式.1");
    }

    return candidates;
  }

  /**
   * 检查真正必须存在的表头。
   *
   * <p>这里直接看Excel真实表头， 不通过columnIndexes二次判断。
   */
  private void validateRequiredHeaders(
      DatasetType datasetType,
      String fallbackPeriod,
      List<FieldDefinition> fields,
      List<String> headers,
      List<ImportError> errors) {

    Set<String> actualHeaders = new HashSet<>();

    for (String header : headers) {

      String normalized = normalizeHeader(header);

      if (!normalized.isBlank()) {

        actualHeaders.add(normalized);
      }
    }

    List<String> required = new ArrayList<>(requiredTechnicalNames(datasetType));

    Map<String, FieldDefinition> fieldsByTechnicalName = new HashMap<>();

    for (FieldDefinition field : fields) {

      fieldsByTechnicalName.put(field.technicalName(), field);
    }

    for (String technicalName : required) {

      FieldDefinition field = fieldsByTechnicalName.get(technicalName);

      /*
       * 正常情况下核心字段都存在于catalog。
       * 如果catalog中意外不存在，仍按技术字段名称检查。
       */
      List<String> acceptedNames =
          field == null ? List.of(technicalName) : candidateHeaderNames(field);

      boolean found = false;

      for (String acceptedName : acceptedNames) {

        if (actualHeaders.contains(normalizeHeader(acceptedName))) {

          found = true;
          break;
        }
      }

      if (!found) {

        addError(errors, 1, technicalName, "REQUIRED_HEADER_MISSING", "缺少必要字段：" + technicalName);
      }
    }
  }

  /**
   * 真正必须存在的业务字段。
   *
   * <p>没有列数限制，只检查这些核心字段。
   */
  private List<String> requiredTechnicalNames(DatasetType datasetType) {

    return switch (datasetType) {
      case BILLING_POINT -> List.of(BILLING_POINT_CODE, BILLING_POINT_NAME, CITY);

      case PAYMENT ->
          List.of(
              BILLING_POINT_CODE,
              BILLING_POINT_NAME,
              CITY,
              PAYMENT_CODE,
              PERIOD_START,
              PERIOD_END,
              AUDIT_STATUS,
              ACTUAL_AMOUNT);

      case METER_READING ->
          List.of(
              BILLING_POINT_CODE,
              PAYMENT_CODE,
              METER_CODE,
              PERIOD_START,
              PERIOD_END,
              ALLOCATED_ENERGY);

      case BENCHMARK ->
          List.of(
              BILLING_POINT_CODE,
              BILLING_POINT_NAME,
              BENCHMARK_CITY,
              YEAR,
              MONTH,
              MONTHLY_BENCHMARK);
    };
  }

  /** 表头标准化。 */
  private String normalizeHeader(String header) {

    if (header == null) {
      return "";
    }

    String normalized = header.replace('\u3000', ' ').trim().replaceAll("\\s+", "");

    /*
     * 有些CSV/Excel工具可能把BOM保留在第一个字段名称里。
     */
    if (!normalized.isEmpty() && normalized.charAt(0) == '\ufeff') {

      normalized = normalized.substring(1);
    }

    if (MONTHLY_AVERAGE_BENCHMARK.equals(normalized)) {

      return MONTHLY_BENCHMARK;
    }

    if ("计费方式.1".equals(normalized) || "计费方式__2".equals(normalized)) {

      return "计费方式";
    }

    return normalized;
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

      addError(errors, sourceRow, BILLING_POINT_NAME, "REQUIRED", "缺少必填字段：" + BILLING_POINT_NAME);
    }

    String rowCity = null;
    String paymentCode = null;
    String meterCode = null;
    String businessKey;

    String period = fallbackPeriod;

    switch (type) {
      case BILLING_POINT -> {
        rowCity = resolveCity(value(values, CITY), cityCodes);

        period = fallbackPeriod == null || fallbackPeriod.isBlank() ? "MASTER" : fallbackPeriod;

        validateDateRange(
            value(values, LAST_PERIOD_START), value(values, LAST_PERIOD_END), sourceRow, errors);

        businessKey = billingPointCode + "|" + sourceRow;
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
            decimal(
                required(values, ACTUAL_AMOUNT, sourceRow, errors),
                sourceRow,
                ACTUAL_AMOUNT,
                errors);

        if (amount != null && amount.signum() < 0) {

          addError(errors, sourceRow, ACTUAL_AMOUNT, "NEGATIVE_VALUE", "实际报账金额不能为负数");
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

          addError(errors, sourceRow, CITY, "CITY_UNKNOWN", "电表读数无法根据缴费单编码或报账点编码推断城市");
        }

        BigDecimal allocated =
            decimal(
                required(values, ALLOCATED_ENERGY, sourceRow, errors),
                sourceRow,
                ALLOCATED_ENERGY,
                errors);

        if (allocated != null && allocated.signum() < 0) {

          addError(errors, sourceRow, ALLOCATED_ENERGY, "NEGATIVE_VALUE", "分摊后度数不能为负数");
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
          "城市无法识别");

    } else if (rowCity != null
        && expectedCityCode != null
        && !expectedCityCode.isBlank()
        && !rowCity.equals(expectedCityCode)) {

      addError(
          errors,
          sourceRow,
          type == DatasetType.BENCHMARK ? BENCHMARK_CITY : CITY,
          "CITY_SCOPE_MISMATCH",
          "文件包含其他城市数据");
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

      addError(errors, sourceRow, PERIOD_END, "DATE_RANGE_INVALID", "缴费期始晚于缴费期终");
    }
  }

  private String inferPeriodFromDate(
      String raw, int sourceRow, String column, List<ImportError> errors) {

    LocalDate date = parseDate(raw);

    if (date == null) {

      addError(errors, sourceRow, column, "DATE_INVALID", "日期格式不正确：" + raw);

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

      addError(errors, sourceRow, YEAR + "/" + MONTH, "PERIOD_INVALID", "年份月份不正确");

      return "";
    }
  }

  private LocalDate parseDate(String raw) {

    if (raw == null || raw.isBlank()) {

      return null;
    }

    String value = raw.trim();

    for (DateTimeFormatter formatter :
        List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"))) {

      try {

        return LocalDate.parse(value, formatter);

      } catch (DateTimeParseException ignored) {

        // 继续尝试下一种支持的日期格式。
      }
    }

    return null;
  }

  private void validateAuditStatus(String status, int sourceRow, List<ImportError> errors) {

    /*
     * 当前业务只要求审核状态字段存在且有值。
     *
     * 原始状态继续保存，
     * 后续汇总逻辑负责判断是否属于审核通过。
     */
  }

  private void validateBenchmark(
      String period, int sourceRow, Map<String, String> values, List<ImportError> errors) {

    if (period == null || period.isBlank()) {

      return;
    }

    YearMonth month = YearMonth.parse(period);

    decimal(
        required(values, MONTHLY_BENCHMARK, sourceRow, errors),
        sourceRow,
        MONTHLY_BENCHMARK,
        errors);

    for (int day = 1; day <= 31; day++) {

      String column = Integer.toString(day);

      String raw = value(values, column);

      if (day <= month.lengthOfMonth()) {

        BigDecimal dayValue = decimal(raw, sourceRow, column, errors);

        if (dayValue != null && dayValue.signum() < 0) {

          addError(errors, sourceRow, column, "NEGATIVE_VALUE", "日标杆值不能为负数");
        }
      }
    }
  }

  private BigDecimal decimal(String raw, int sourceRow, String column, List<ImportError> errors) {

    String normalized = raw == null ? "" : raw.trim().replace(",", "").replace("%", "");

    if (normalized.isBlank()
        || "-".equals(normalized)
        || "—".equals(normalized)
        || "–".equals(normalized)) {

      return null;
    }

    try {

      return new BigDecimal(normalized);

    } catch (RuntimeException exception) {

      addError(errors, sourceRow, column, "DECIMAL_INVALID", "数值格式不正确：" + raw);

      return null;
    }
  }

  private String required(
      Map<String, String> values, String column, int sourceRow, List<ImportError> errors) {

    String raw = value(values, column);

    if (raw.isBlank()) {

      addError(errors, sourceRow, column, "REQUIRED", "缺少必填字段：" + column);
    }

    return raw;
  }

  private Map<String, String> cityCodes() {

    Map<String, String> map = new LinkedHashMap<>();

    cityQueryService
        .findAll()
        .forEach(
            city -> {
              map.put(city.code(), city.code());

              map.put(city.name(), city.code());

              if (city.name().endsWith("市")) {

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

    if (!normalized.endsWith("市")) {

      city = cityCodes.get(normalized + "市");
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
