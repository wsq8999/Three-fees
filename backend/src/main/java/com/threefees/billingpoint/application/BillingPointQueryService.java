package com.threefees.billingpoint.application;

import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import com.threefees.importing.application.FieldCatalogService;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.FieldDefinition;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class BillingPointQueryService {

  private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP =
      new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final FieldCatalogService fieldCatalogService;

  public BillingPointQueryService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      FieldCatalogService fieldCatalogService) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.fieldCatalogService = fieldCatalogService;
  }

  @Transactional(readOnly = true)
  public PageResult findPage(BillingPointFilter filter, int page, int size, CurrentUser actor) {
    String cityScope = cityScope(actor, filter.cityCode());
    String period = filter.period() == null || filter.period().isBlank() ? null : filter.period();

    List<BillingPointSummary> all =
        loadSummaries(period, cityScope, filter.focusPeriod(), filter.focusCityCode());

    Predicate<BillingPointSummary> predicate = filters(filter);
    List<BillingPointSummary> filtered = all.stream().filter(predicate).toList();

    int from = Math.min(Math.multiplyExact(page, size), filtered.size());
    int to = Math.min(from + size, filtered.size());
    int totalPages = filtered.isEmpty() ? 0 : (filtered.size() + size - 1) / size;

    return new PageResult(
        filtered.subList(from, to),
        page,
        size,
        filtered.size(),
        totalPages,
        period == null ? "" : period);
  }

  @Transactional(readOnly = true)
  public BillingPointDetail findDetail(String publicId, CurrentUser actor) {
    SnapshotRow snapshot = findSnapshot(publicId);
    requireCityScope(actor, snapshot.cityCode());

    BillingPointSummary summary =
        loadSummaries(snapshot.period(), snapshot.cityCode(), null, null).stream()
            .filter(item -> item.id().equals(publicId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("报账点账期"));

    List<RecordDetail> payments = records(DatasetType.PAYMENT, snapshot);
    List<RecordDetail> meters = records(DatasetType.METER_READING, snapshot);
    List<RecordDetail> benchmarks = records(DatasetType.BENCHMARK, snapshot);

    List<FieldValue> overview =
        fieldValues(DatasetType.BILLING_POINT, readMap(snapshot.dataJson()));

    JsonNode audit = loadAudit(snapshot);

    return new BillingPointDetail(
        summary,
        group(overview),
        overview,
        payments,
        meters,
        benchmarks,
        audit,
        summary.draftId(),
        summary.reportId());
  }

  @Transactional(readOnly = true)
  public FilterOptions filterOptions(CurrentUser actor) {
    String cityScope = actor.roles().contains(Role.SUPER_ADMIN) ? null : actor.cityCode();

    var sql = new StringBuilder();
    sql.append(
        """
        SELECT s.data_period, s.city_code, c.name AS city_name, s.data_json
          FROM billing_point_snapshot s
          JOIN city c ON c.code = s.city_code
         WHERE 1 = 1
        """);

    var arguments = new ArrayList<>();

    if (cityScope != null) {
      sql.append(" AND s.city_code = ?");
      arguments.add(cityScope);
    }

    var periods = new java.util.TreeSet<String>(java.util.Comparator.reverseOrder());
    var cities = new LinkedHashMap<String, String>();
    var districts = new java.util.TreeSet<String>();

    jdbcTemplate.query(
        sql.toString(),
        resultSet -> {
          periods.add(resultSet.getString("data_period"));
          cities.put(resultSet.getString("city_code"), resultSet.getString("city_name"));

          String district = readMap(resultSet.getString("data_json")).get("所属区县");
          if (district != null && !district.isBlank()) {
            districts.add(district);
          }
        },
        arguments.toArray());

    return new FilterOptions(
        List.copyOf(periods),
        cities.entrySet().stream()
            .map(entry -> new CityOption(entry.getKey(), entry.getValue()))
            .toList(),
        List.copyOf(districts));
  }

  private List<BillingPointSummary> loadSummaries(
      String period, String cityScope, String focusPeriod, String focusCityCode) {

    var sql = new StringBuilder();
    sql.append(
        """
        SELECT s.public_id, s.billing_point_code, s.billing_point_name, s.city_code,
               c.name AS city_name, s.data_period, s.period_start, s.period_end, s.data_json,
               a.payment_eligible, a.actual_energy, a.actual_amount, a.rated_benchmark_energy,
               a.max_ratio, a.yoy_ratio, a.mom_ratio, a.rated_ratio,
               a.audit_status, a.over_limit_type,
               a.yoy_result, a.mom_result, a.rated_result,
               d.public_id AS draft_id, d.analysis_status AS draft_analysis_status,
               r.public_id AS report_id, r.report_number
          FROM billing_point_snapshot s
          JOIN city c ON c.code = s.city_code
          LEFT JOIN audit_result a
            ON a.billing_point_code = s.billing_point_code
           AND a.data_period = s.data_period
           AND a.city_code = s.city_code
          LEFT JOIN report_draft d
            ON d.billing_point_snapshot_id = s.id
          LEFT JOIN audit_report r
            ON r.billing_point_snapshot_id = s.id
          LEFT JOIN import_job ij
            ON ij.id = s.source_import_job_id
         WHERE 1 = 1
        """);

    var arguments = new ArrayList<>();

    if (period != null && !period.isBlank()) {
      sql.append(" AND s.data_period = ?");
      arguments.add(period);
    }

    if (cityScope != null && !cityScope.isBlank()) {
      sql.append(" AND s.city_code = ?");
      arguments.add(cityScope);
    }

    /*
     * 排序规则：
     *
     * 1. 先把操作栏会显示“生成报告”的报账点整体放到最前面。
     *    前端“生成报告”按钮的条件是 reportStatus = DRAFT；
     *    后端对应：
     *      audit_status = OVER_LIMIT
     *      且尚未存在正式报告 r.id IS NULL
     *
     * 2. 在“需要生成报告”和“其他报账点”各自内部，
     *    完整保留原有排序逻辑：
     *      - 有 focusPeriod/focusCityCode 时，原焦点排序继续生效；
     *      - 然后按导入/更新时间倒序；
     *      - 再按账期倒序、最高超标比例倒序等原规则排序。
     *
     * 这样可以保证：
     * “有生成报告按钮的全部置顶 + 每个分组内部仍按原来的倒序逻辑”。
     *
     * 该排序发生在分页之前，所以即使需要生成报告的数据原本在第2页、第3页，
     * 也会先被整体提到前面的分页中，而不是只对当前10条做前端排序。
     */
    sql.append(
        """
         ORDER BY
           CASE
             WHEN a.audit_status = 'OVER_LIMIT' AND r.id IS NULL THEN 0
             ELSE 1
           END,
        """);

    if (focusPeriod != null && !focusPeriod.isBlank()) {
      sql.append(" CASE WHEN s.data_period = ?");
      arguments.add(focusPeriod);

      if (focusCityCode != null && !focusCityCode.isBlank()) {
        sql.append(" AND s.city_code = ?");
        arguments.add(focusCityCode);
      }

      sql.append(" THEN 0 ELSE 1 END,");
    }

    sql.append(
        """
           COALESCE(ij.completed_at, ij.updated_at, ij.created_at, s.updated_at) DESC,
           s.data_period DESC,
           a.max_ratio DESC,
           s.billing_point_code ASC,
           s.id ASC
        """);

    return jdbcTemplate.query(sql.toString(), this::mapSummary, arguments.toArray());
  }

  private BillingPointSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
    Map<String, String> values = readMap(resultSet.getString("data_json"));

    String auditStatus = valueOr(resultSet.getString("audit_status"), "NOT_APPLICABLE");
    String reportId = resultSet.getString("report_id");

    String reportStatus =
        !"OVER_LIMIT".equals(auditStatus)
            ? "NONE"
            : reportId == null ? "PENDING" : "GENERATED";

    List<String> paymentCodes =
        paymentCodes(
            resultSet.getString("data_period"),
            resultSet.getString("city_code"),
            resultSet.getString("billing_point_code"));

    return new BillingPointSummary(
        resultSet.getString("public_id"),
        resultSet.getString("billing_point_code"),
        resultSet.getString("billing_point_name"),
        new CityValue(resultSet.getString("city_code"), resultSet.getString("city_name")),
        values.get("所属区县"),
        values.get("关联资源名称"),
        values.get("用电类别"),
        values.get("报账点状态"),
        resultSet.getString("data_period"),
        resultSet.getObject("period_start", LocalDate.class),
        resultSet.getObject("period_end", LocalDate.class),
        paymentCodes,
        Boolean.TRUE.equals(resultSet.getObject("payment_eligible", Boolean.class)),
        decimalString(resultSet.getBigDecimal("actual_energy")),
        decimalString(resultSet.getBigDecimal("actual_amount")),
        decimalString(resultSet.getBigDecimal("rated_benchmark_energy")),
        decimalString(resultSet.getBigDecimal("max_ratio")),
        overLimitRatios(resultSet),
        auditStatus,
        valueOr(resultSet.getString("over_limit_type"), "NONE"),
        overLimitDisplayType(resultSet),
        reportStatus,
        resultSet.getString("draft_id"),
        resultSet.getString("draft_analysis_status"),
        reportId,
        resultSet.getString("report_number"));
  }

  private List<String> paymentCodes(String period, String cityCode, String billingPointCode) {
    return jdbcTemplate.queryForList(
        """
        SELECT DISTINCT payment_bill_code
          FROM payment_detail
         WHERE data_period = ?
           AND city_code = ?
           AND billing_point_code = ?
         ORDER BY payment_bill_code
        """,
        String.class,
        period,
        cityCode,
        billingPointCode);
  }

  private List<RecordDetail> records(DatasetType type, SnapshotRow snapshot) {
    return formalRows(type, snapshot).stream()
        .map(
            row -> {
              List<FieldValue> fields = fieldValues(type, readMap(row.json()));
              return new RecordDetail(
                  Long.toString(row.id()),
                  row.paymentCode(),
                  row.meterCode(),
                  group(fields),
                  fields);
            })
        .toList();
  }

  private List<RowValue> formalRows(DatasetType type, SnapshotRow snapshot) {
    return switch (type) {
      case PAYMENT ->
          jdbcTemplate.query(
              """
              SELECT id,
                     payment_bill_code AS payment_code,
                     NULL AS meter_code,
                     values_json
                FROM payment_detail
               WHERE data_period = ?
                 AND city_code = ?
                 AND billing_point_code = ?
               ORDER BY id
              """,
              this::mapRowValue,
              snapshot.period(),
              snapshot.cityCode(),
              snapshot.billingPointCode());

      case METER_READING ->
          jdbcTemplate.query(
              """
              SELECT id,
                     payment_bill_code AS payment_code,
                     meter_code,
                     values_json
                FROM meter_reading
               WHERE data_period = ?
                 AND city_code = ?
                 AND billing_point_code = ?
               ORDER BY id
              """,
              this::mapRowValue,
              snapshot.period(),
              snapshot.cityCode(),
              snapshot.billingPointCode());

      case BENCHMARK ->
          jdbcTemplate.query(
              """
              SELECT id,
                     NULL AS payment_code,
                     NULL AS meter_code,
                     values_json
                FROM benchmark_value
               WHERE data_period = ?
                 AND city_code = ?
                 AND billing_point_code = ?
               ORDER BY id
              """,
              this::mapRowValue,
              snapshot.period(),
              snapshot.cityCode(),
              snapshot.billingPointCode());

      case BILLING_POINT -> List.of();
    };
  }

  private RowValue mapRowValue(ResultSet resultSet, int rowNumber) throws SQLException {
    return new RowValue(
        resultSet.getLong("id"),
        resultSet.getString("payment_code"),
        resultSet.getString("meter_code"),
        resultSet.getString("values_json"));
  }

  private List<FieldValue> fieldValues(DatasetType type, Map<String, String> values) {
    return fieldCatalogService.fields(type).stream()
        .map(field -> value(field, values.get(field.technicalName())))
        .toList();
  }

  private FieldValue value(FieldDefinition field, String value) {
    return new FieldValue(
        field.order(),
        field.technicalName(),
        field.sourceName(),
        field.businessGroup(),
        field.suggestedType(),
        value == null || value.isBlank() ? null : value);
  }

  private List<FieldGroup> group(List<FieldValue> fields) {
    var groups = new LinkedHashMap<String, List<FieldValue>>();

    for (FieldValue field : fields) {
      groups.computeIfAbsent(field.group(), ignored -> new ArrayList<>()).add(field);
    }

    return groups.entrySet().stream()
        .map(entry -> new FieldGroup(entry.getKey(), List.copyOf(entry.getValue())))
        .toList();
  }

  private JsonNode loadAudit(SnapshotRow snapshot) {
    List<JsonNode> audits =
        jdbcTemplate.query(
            """
            SELECT audit_status,
                   over_limit_type,
                   max_ratio,
                   actual_energy,
                   actual_amount,
                   rated_benchmark_energy,
                   calculated_at,
                   payment_eligibility_reason,
                   yoy_reference_period,
                   yoy_reference_energy,
                   yoy_threshold_daily_kwh,
                   mom_reference_period,
                   mom_reference_energy,
                   mom_threshold_daily_kwh,
                   rated_benchmark_energy,
                   yoy_ratio,
                   mom_ratio,
                   rated_ratio,
                   detail_json,
                   yoy_result,
                   mom_result,
                   rated_result,
                   yoy_na_reason,
                   mom_na_reason,
                   rated_na_reason
              FROM audit_result
             WHERE billing_point_code = ?
               AND data_period = ?
               AND city_code = ?
            """,
            (resultSet, rowNumber) -> normalizedAudit(resultSet),
            snapshot.billingPointCode(),
            snapshot.period(),
            snapshot.cityCode());

    return audits.isEmpty() ? pendingAudit() : audits.getFirst();
  }

  private JsonNode pendingAudit() {
    var root = objectMapper.createObjectNode();

    root.put("finalStatus", "PENDING_REVIEW");
    root.put("finalReason", "当前报账点尚未完成稽核，请确认四类文件已导入成功");
    root.put("ruleVersion", "CURRENT");
    root.putNull("calculatedAt");
    root.put("eligibilityReason", "当前报账点尚未完成稽核");

    var comparisons = root.putArray("comparisons");

    comparisons.add(
        comparison(
            "YEAR_ON_YEAR",
            "同比",
            "NA",
            null,
            null,
            null,
            null,
            null,
            "尚未生成同比稽核结果",
            "本期实际用电与去年同月正常上限比较"));

    comparisons.add(
        comparison(
            "MONTH_ON_MONTH",
            "环比",
            "NA",
            null,
            null,
            null,
            null,
            null,
            "尚未生成环比稽核结果",
            "本期实际用电与上一个自然月正常上限比较"));

    comparisons.add(
        comparison(
            "RATED_BENCHMARK",
            "额定标杆",
            "NA",
            null,
            null,
            null,
            null,
            null,
            "尚未生成额定标杆稽核结果",
            "本期实际用电与当月日标杆合计比较"));

    root.set("raw", objectMapper.createObjectNode());
    return root;
  }

  private JsonNode normalizedAudit(ResultSet resultSet) throws SQLException {
    var root = objectMapper.createObjectNode();

    root.put(
        "finalStatus",
        valueOr(resultSet.getString("audit_status"), "NOT_APPLICABLE"));

    root.put("finalReason", auditReason(resultSet));
    root.put("ruleVersion", "CURRENT");

    var calculatedAt = resultSet.getTimestamp("calculated_at");
    root.put(
        "calculatedAt",
        calculatedAt == null ? null : calculatedAt.toInstant().toString());

    root.put(
        "eligibilityReason",
        valueOr(
            resultSet.getString("payment_eligibility_reason"),
            "稽核已按当前正式数据计算"));

    var comparisons = root.putArray("comparisons");

    comparisons.add(
        comparison(
            "YEAR_ON_YEAR",
            "同比",
            resultSet.getString("yoy_result"),
            resultSet.getString("yoy_reference_period"),
            resultSet.getBigDecimal("yoy_reference_energy"),
            resultSet.getBigDecimal("yoy_threshold_daily_kwh"),
            resultSet.getBigDecimal("actual_energy"),
            resultSet.getBigDecimal("yoy_ratio"),
            resultSet.getString("yoy_na_reason"),
            "本期实际用电与去年同月正常上限比较"));

    comparisons.add(
        comparison(
            "MONTH_ON_MONTH",
            "环比",
            resultSet.getString("mom_result"),
            resultSet.getString("mom_reference_period"),
            resultSet.getBigDecimal("mom_reference_energy"),
            resultSet.getBigDecimal("mom_threshold_daily_kwh"),
            resultSet.getBigDecimal("actual_energy"),
            resultSet.getBigDecimal("mom_ratio"),
            resultSet.getString("mom_na_reason"),
            "本期实际用电与上一个自然月正常上限比较"));

    comparisons.add(
        comparison(
            "RATED_BENCHMARK",
            "额定标杆",
            resultSet.getString("rated_result"),
            null,
            resultSet.getBigDecimal("rated_benchmark_energy"),
            resultSet.getBigDecimal("rated_benchmark_energy"),
            resultSet.getBigDecimal("actual_energy"),
            resultSet.getBigDecimal("rated_ratio"),
            resultSet.getString("rated_na_reason"),
            "本期实际用电与当月日标杆合计比较"));

    root.set("raw", parseAuditDetail(resultSet.getString("detail_json")));
    return root;
  }

  private JsonNode comparison(
      String key,
      String label,
      String result,
      String referencePeriod,
      BigDecimal baseline,
      BigDecimal threshold,
      BigDecimal actual,
      BigDecimal ratio,
      String naReason,
      String formula) {

    var node = objectMapper.createObjectNode();

    String status =
        "OVER_LIMIT".equals(result)
            ? "OVER_LIMIT"
            : "NORMAL".equals(result)
                ? "NORMAL"
                : "NOT_APPLICABLE";

    node.put("key", key);
    node.put("label", label);
    node.put("status", status);
    node.put("referencePeriod", referencePeriod);
    node.put("baseline", decimalString(baseline));
    node.put("threshold", decimalString(threshold));
    node.put("actual", decimalString(actual));
    node.put("difference", difference(actual, threshold));
    node.put("ratio", decimalString(ratio));

    node.put(
        "reason",
        status.equals("NOT_APPLICABLE")
            ? valueOr(naReason, "参考数据不足，暂不适用")
            : status.equals("OVER_LIMIT")
                ? label + "超标"
                : label + "正常");

    node.put("formula", formula);

    return node;
  }

  private JsonNode parseAuditDetail(String value) {
    if (value == null || value.isBlank()) {
      return objectMapper.createObjectNode();
    }

    try {
      return objectMapper.readTree(value);
    } catch (JacksonException exception) {
      return objectMapper.createObjectNode();
    }
  }

  private String auditReason(ResultSet resultSet) throws SQLException {
    String status = resultSet.getString("audit_status");

    if ("OVER_LIMIT".equals(status)) {
      return "稽核结果超标，超标类型："
          + overLimitTypeLabel(resultSet.getString("over_limit_type"));
    }

    if ("NORMAL".equals(status)) {
      return "稽核结果正常";
    }

    return "稽核结果暂不适用";
  }

  private String overLimitTypeLabel(String value) {
    if (value == null || value.isBlank()) {
      return "未分类";
    }

    return switch (value) {
      case "ONLY_YOY" -> "仅同比超标";
      case "ONLY_MOM" -> "仅环比超标";
      case "ONLY_RATED" -> "仅额定标杆超标";
      case "MULTIPLE" -> "多项超标";
      case "NONE" -> "未超标";
      default -> value;
    };
  }

  private String overLimitDisplayType(ResultSet resultSet) throws SQLException {
    String overLimitType = resultSet.getString("over_limit_type");
    if (!"MULTIPLE".equals(overLimitType)) {
      return overLimitTypeLabel(overLimitType);
    }

    var labels = new ArrayList<String>();
    if ("OVER_LIMIT".equals(resultSet.getString("yoy_result"))) {
      labels.add("同比");
    }
    if ("OVER_LIMIT".equals(resultSet.getString("mom_result"))) {
      labels.add("环比");
    }
    if ("OVER_LIMIT".equals(resultSet.getString("rated_result"))) {
      labels.add("额定标杆");
    }

    return labels.isEmpty() ? "超标" : String.join("、", labels) + "超标";
  }

  private List<OverLimitRatio> overLimitRatios(ResultSet resultSet) throws SQLException {
    var ratios = new ArrayList<OverLimitRatio>();
    addOverLimitRatio(ratios, resultSet, "yoy_result", "yoy_ratio", "YOY", "同比");
    addOverLimitRatio(ratios, resultSet, "mom_result", "mom_ratio", "MOM", "环比");
    addOverLimitRatio(ratios, resultSet, "rated_result", "rated_ratio", "RATED", "额定标杆");
    return ratios;
  }

  private void addOverLimitRatio(
      List<OverLimitRatio> ratios,
      ResultSet resultSet,
      String resultColumn,
      String ratioColumn,
      String type,
      String label)
      throws SQLException {
    if ("OVER_LIMIT".equals(resultSet.getString(resultColumn))) {
      ratios.add(new OverLimitRatio(type, label, decimalString(resultSet.getBigDecimal(ratioColumn))));
    }
  }

  private String difference(BigDecimal actual, BigDecimal baseline) {
    if (actual == null || baseline == null) {
      return null;
    }

    return decimalString(actual.subtract(baseline));
  }

  private SnapshotRow findSnapshot(String publicId) {
    return jdbcTemplate
        .query(
            """
            SELECT s.public_id,
                   s.billing_point_code,
                   s.city_code,
                   s.data_period,
                   s.data_json
              FROM billing_point_snapshot s
             WHERE s.public_id = ?
            """,
            (resultSet, rowNumber) ->
                new SnapshotRow(
                    resultSet.getString("public_id"),
                    resultSet.getString("billing_point_code"),
                    resultSet.getString("city_code"),
                    resultSet.getString("data_period"),
                    resultSet.getString("data_json")),
            publicId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("报账点账期"));
  }

  private Predicate<BillingPointSummary> filters(BillingPointFilter filter) {
    return summary ->
        contains(summary.code(), filter.code())
            && contains(summary.name(), filter.name())
            && contains(summary.district(), filter.district())
            && contains(summary.siteName(), filter.siteKeyword())
            && contains(String.join(" ", summary.paymentCodes()), filter.paymentKeyword())
            && equalsFilter(summary.billingPointStatus(), filter.billingPointStatus())
            && equalsFilter(summary.auditStatus(), filter.auditStatus())
            && equalsFilter(summary.reportStatus(), filter.reportStatus())
            && (filter.paymentEligible() == null
                || summary.paymentEligible() == filter.paymentEligible());
  }

  private boolean contains(String value, String filter) {
    return filter == null
        || filter.isBlank()
        || (value != null
            && value.toLowerCase().contains(filter.trim().toLowerCase()));
  }

  private boolean equalsFilter(String value, String filter) {
    return filter == null
        || filter.isBlank()
        || Objects.equals(value, filter);
  }

  private String cityScope(CurrentUser actor, String requestedCityCode) {
    if (!actor.roles().contains(Role.SUPER_ADMIN)) {
      if (requestedCityCode != null
          && !requestedCityCode.isBlank()
          && !requestedCityCode.equals(actor.cityCode())) {
        throw new AccessDeniedException("City scope mismatch");
      }

      return actor.cityCode();
    }

    return requestedCityCode;
  }

  private void requireCityScope(CurrentUser actor, String cityCode) {
    if (!actor.roles().contains(Role.SUPER_ADMIN)
        && !actor.cityCode().equals(cityCode)) {
      throw new AccessDeniedException("Billing point is outside city scope");
    }
  }

  private Map<String, String> readMap(String json) {
    try {
      return objectMapper.readValue(json, STRING_MAP);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Persisted billing data is invalid JSON",
          exception);
    }
  }

  private String decimalString(BigDecimal value) {
    return value == null
        ? null
        : value.stripTrailingZeros().toPlainString();
  }

  private String valueOr(String value, String fallback) {
    return value == null ? fallback : value;
  }

  public record BillingPointFilter(
      String code,
      String name,
      String cityCode,
      String district,
      String period,
      String siteKeyword,
      String paymentKeyword,
      Boolean paymentEligible,
      String billingPointStatus,
      String auditStatus,
      String reportStatus,
      String focusPeriod,
      String focusCityCode) {}

  public record PageResult(
      List<BillingPointSummary> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      String resolvedPeriod) {}

  public record BillingPointSummary(
      String id,
      String code,
      String name,
      CityValue city,
      String district,
      String siteName,
      String electricityCategory,
      String billingPointStatus,
      String period,
      LocalDate periodStart,
      LocalDate periodEnd,
      List<String> paymentCodes,
      boolean paymentEligible,
      String actualEnergy,
      String actualAmount,
      String benchmarkEnergy,
      String maxDeviationRate,
      List<OverLimitRatio> overLimitRatios,
      String auditStatus,
      String overLimitType,
      String overLimitDisplayType,
      String reportStatus,
      String draftId,
      String draftAnalysisStatus,
      String reportId,
      String reportNumber) {}

  public record OverLimitRatio(String type, String label, String ratio) {}

  public record CityValue(String code, String name) {}

  public record BillingPointDetail(
      BillingPointSummary summary,
      List<FieldGroup> overviewGroups,
      List<FieldValue> overviewFields,
      List<RecordDetail> payments,
      List<RecordDetail> meters,
      List<RecordDetail> benchmarks,
      JsonNode audit,
      String draftId,
      String reportId) {}

  public record FieldGroup(
      String name,
      List<FieldValue> fields) {}

  public record FieldValue(
      int order,
      String name,
      String sourceName,
      String group,
      String type,
      String value) {}

  public record RecordDetail(
      String id,
      String paymentCode,
      String meterCode,
      List<FieldGroup> fieldGroups,
      List<FieldValue> fields) {}

  public record FilterOptions(
      List<String> periods,
      List<CityOption> cities,
      List<String> districts) {}

  public record CityOption(
      String code,
      String name) {}

  private record SnapshotRow(
      String publicId,
      String billingPointCode,
      String cityCode,
      String period,
      String dataJson) {}

  private record RowValue(
      long id,
      String paymentCode,
      String meterCode,
      String json) {}
}
