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
    String period =
        filter.period() == null || filter.period().isBlank()
            ? latestPeriod(cityScope)
            : filter.period();
    if (period == null) {
      return new PageResult(List.of(), page, size, 0, 0, null);
    }
    List<BillingPointSummary> all = loadSummaries(period, cityScope);
    Predicate<BillingPointSummary> predicate = filters(filter);
    List<BillingPointSummary> filtered = all.stream().filter(predicate).toList();
    int from = Math.min(Math.multiplyExact(page, size), filtered.size());
    int to = Math.min(from + size, filtered.size());
    int totalPages = filtered.isEmpty() ? 0 : (filtered.size() + size - 1) / size;
    return new PageResult(
        filtered.subList(from, to), page, size, filtered.size(), totalPages, period);
  }

  @Transactional(readOnly = true)
  public BillingPointDetail findDetail(String publicId, CurrentUser actor) {
    SnapshotRow snapshot = findSnapshot(publicId);
    requireCityScope(actor, snapshot.cityCode());
    BillingPointSummary summary =
        loadSummaries(snapshot.period(), snapshot.cityCode()).stream()
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
          JOIN import_batch b ON b.id = s.source_batch_id AND b.status = 'ACTIVE'
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

  private List<BillingPointSummary> loadSummaries(String period, String cityScope) {
    var sql = new StringBuilder();
    sql.append(
        """
        SELECT s.public_id, s.billing_point_code, s.billing_point_name, s.city_code,
               c.name AS city_name, s.data_period, s.period_start, s.period_end, s.data_json,
               a.payment_eligible, a.actual_energy, a.actual_amount, a.rated_benchmark_energy,
               a.max_ratio, a.audit_status, a.over_limit_type,
               d.public_id AS draft_id, r.public_id AS report_id, r.report_number
          FROM billing_point_snapshot s
          JOIN import_batch active_batch
            ON active_batch.id = s.source_batch_id AND active_batch.status = 'ACTIVE'
          JOIN city c ON c.code = s.city_code
          LEFT JOIN audit_result a
            ON a.billing_point_code = s.billing_point_code AND a.data_period = s.data_period
          LEFT JOIN report_draft d ON d.billing_point_snapshot_id = s.id
          LEFT JOIN audit_report r ON r.billing_point_snapshot_id = s.id
         WHERE s.data_period = ?
        """);
    var arguments = new ArrayList<>();
    arguments.add(period);
    if (cityScope != null && !cityScope.isBlank()) {
      sql.append(" AND s.city_code = ?");
      arguments.add(cityScope);
    }
    sql.append(
        """
         ORDER BY CASE WHEN a.audit_status = 'OVER_LIMIT' AND r.id IS NULL THEN 0 ELSE 1 END,
                  a.max_ratio DESC, s.billing_point_code ASC, s.id ASC
        """);
    return jdbcTemplate.query(sql.toString(), this::mapSummary, arguments.toArray());
  }

  private BillingPointSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
    Map<String, String> values = readMap(resultSet.getString("data_json"));
    String auditStatus = valueOr(resultSet.getString("audit_status"), "NOT_APPLICABLE");
    String reportId = resultSet.getString("report_id");
    String reportStatus =
        !"OVER_LIMIT".equals(auditStatus) ? "NONE" : reportId == null ? "PENDING" : "GENERATED";
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
        auditStatus,
        valueOr(resultSet.getString("over_limit_type"), "NONE"),
        reportStatus,
        resultSet.getString("draft_id"),
        reportId,
        resultSet.getString("report_number"));
  }

  private List<String> paymentCodes(String period, String cityCode, String billingPointCode) {
    return jdbcTemplate.queryForList(
        """
        SELECT DISTINCT r.payment_code
          FROM imported_record r
          JOIN import_batch b ON b.id = r.batch_id AND b.status = 'ACTIVE'
         WHERE r.dataset_type = 'PAYMENT' AND r.data_period = ? AND r.city_code = ?
           AND r.billing_point_code = ? AND r.is_active = TRUE AND r.payment_code IS NOT NULL
         ORDER BY r.payment_code
        """,
        String.class,
        period,
        cityCode,
        billingPointCode);
  }

  private List<RecordDetail> records(DatasetType type, SnapshotRow snapshot) {
    List<RowValue> rows =
        jdbcTemplate.query(
            """
            SELECT r.id, r.payment_code, r.meter_code, r.values_json
              FROM imported_record r
              JOIN import_batch b ON b.id = r.batch_id AND b.status = 'ACTIVE'
             WHERE r.dataset_type = ? AND r.data_period = ? AND r.city_code = ?
               AND r.billing_point_code = ? AND r.is_active = TRUE
             ORDER BY r.id
            """,
            (resultSet, rowNumber) ->
                new RowValue(
                    resultSet.getLong("id"),
                    resultSet.getString("payment_code"),
                    resultSet.getString("meter_code"),
                    resultSet.getString("values_json")),
            type.name(),
            snapshot.period(),
            snapshot.cityCode(),
            snapshot.billingPointCode());
    return rows.stream()
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
    List<String> details =
        jdbcTemplate.queryForList(
            """
            SELECT detail_json FROM audit_result
             WHERE billing_point_code = ? AND data_period = ?
            """,
            String.class,
            snapshot.billingPointCode(),
            snapshot.period());
    if (details.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.readTree(details.getFirst());
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted audit detail is invalid JSON", exception);
    }
  }

  private SnapshotRow findSnapshot(String publicId) {
    return jdbcTemplate
        .query(
            """
            SELECT s.public_id, s.billing_point_code, s.city_code, s.data_period, s.data_json
              FROM billing_point_snapshot s
              JOIN import_batch b ON b.id = s.source_batch_id AND b.status = 'ACTIVE'
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
        || (value != null && value.toLowerCase().contains(filter.trim().toLowerCase()));
  }

  private boolean equalsFilter(String value, String filter) {
    return filter == null || filter.isBlank() || Objects.equals(value, filter);
  }

  private String latestPeriod(String cityScope) {
    String sql =
        """
        SELECT MAX(s.data_period)
          FROM billing_point_snapshot s
          JOIN import_batch b ON b.id = s.source_batch_id AND b.status = 'ACTIVE'
        """
            + (cityScope == null ? "" : " WHERE s.city_code = ?");
    return cityScope == null
        ? jdbcTemplate.queryForObject(sql, String.class)
        : jdbcTemplate.queryForObject(sql, String.class, cityScope);
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
    if (!actor.roles().contains(Role.SUPER_ADMIN) && !actor.cityCode().equals(cityCode)) {
      throw new AccessDeniedException("Billing point is outside city scope");
    }
  }

  private Map<String, String> readMap(String json) {
    try {
      return objectMapper.readValue(json, STRING_MAP);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted billing data is invalid JSON", exception);
    }
  }

  private String decimalString(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
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
      String reportStatus) {}

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
      String auditStatus,
      String overLimitType,
      String reportStatus,
      String draftId,
      String reportId,
      String reportNumber) {}

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

  public record FieldGroup(String name, List<FieldValue> fields) {}

  public record FieldValue(
      int order, String name, String sourceName, String group, String type, String value) {}

  public record RecordDetail(
      String id,
      String paymentCode,
      String meterCode,
      List<FieldGroup> fieldGroups,
      List<FieldValue> fields) {}

  public record FilterOptions(
      List<String> periods, List<CityOption> cities, List<String> districts) {}

  public record CityOption(String code, String name) {}

  private record SnapshotRow(
      String publicId, String billingPointCode, String cityCode, String period, String dataJson) {}

  private record RowValue(long id, String paymentCode, String meterCode, String json) {}
}
