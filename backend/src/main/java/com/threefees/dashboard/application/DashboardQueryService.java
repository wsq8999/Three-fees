package com.threefees.dashboard.application;

import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import com.threefees.organization.application.CityQueryService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardQueryService {

  private static final List<String> DATASET_TYPES =
      List.of("BILLING_POINT", "PAYMENT", "METER_READING", "BENCHMARK");

  private final CityQueryService cityQueryService;
  private final JdbcTemplate jdbcTemplate;

  public DashboardQueryService(CityQueryService cityQueryService, JdbcTemplate jdbcTemplate) {
    this.cityQueryService = cityQueryService;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional(readOnly = true)
  public DashboardSummary summarize(CurrentUser actor, String requestedPeriod) {
    String cityScope = cityScope(actor);
    List<String> periods = availablePeriods(cityScope);
    String latestPeriod = periods.isEmpty() ? null : periods.getFirst();
    String period =
        requestedPeriod != null && periods.contains(requestedPeriod)
            ? requestedPeriod
            : latestPeriod;
    int visibleCityCount = actor.roles().contains(Role.SUPER_ADMIN) ? cityQueryService.count() : 1;
    if (period == null) {
      return emptySummary(visibleCityCount, periods);
    }

    long pendingReportCount = countPendingReports(period, cityScope);
    return new DashboardSummary(
        period,
        periods,
        visibleCityCount,
        countSnapshots(period, cityScope),
        countAuditStatus(period, cityScope, "OVER_LIMIT"),
        pendingReportCount,
        pendingReportCount,
        countDistinctSite(period, cityScope),
        lastUpdatedAt(period, cityScope),
        countAuditStatus(period, cityScope, "NORMAL"),
        countPendingAudit(period, cityScope),
        countReports(period, cityScope),
        importSummaries(period, cityScope),
        districtOverLimitCounts(period, cityScope),
        overLimitTypeCounts(period, cityScope),
        pendingTasks(period, cityScope));
  }

  private DashboardSummary emptySummary(int visibleCityCount, List<String> periods) {
    return new DashboardSummary(
        null,
        periods,
        visibleCityCount,
        0,
        0,
        0,
        0,
        0,
        null,
        0,
        0,
        0,
        DATASET_TYPES.stream()
            .map(type -> new DashboardSummary.DatasetImportSummary(type, null))
            .toList(),
        List.of(),
        List.of(),
        List.of());
  }

  private String cityScope(CurrentUser actor) {
    return actor.roles().contains(Role.SUPER_ADMIN) ? null : actor.cityCode();
  }

  private List<String> availablePeriods(String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT DISTINCT data_period
              FROM billing_point_snapshot
             WHERE 1 = 1
            """);
    var args = new ArrayList<>();
    appendCityScope(sql, args, cityScope, null);
    sql.append(" ORDER BY data_period DESC");
    return jdbcTemplate.queryForList(sql.toString(), String.class, args.toArray());
  }

  private long countSnapshots(String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT COUNT(*)
              FROM (
                    SELECT DISTINCT s.billing_point_code, s.data_period, s.city_code
                      FROM billing_point_snapshot s
                     WHERE s.data_period = ?
                   """);
    var args = new ArrayList<>();
    args.add(period);
    appendCityScope(sql, args, cityScope, "s");
    sql.append(") snapshots");
    return number(sql.toString(), args);
  }

  private long countAuditStatus(String period, String cityScope, String auditStatus) {
    var sql =
        new StringBuilder(
            """
            SELECT COUNT(*)
              FROM (
                    SELECT DISTINCT s.billing_point_code, s.data_period, s.city_code
                      FROM billing_point_snapshot s
                      JOIN audit_result a
                        ON a.billing_point_code = s.billing_point_code
                       AND a.data_period = s.data_period
                       AND a.city_code = s.city_code
                     WHERE s.data_period = ?
                       AND a.audit_status = ?
                   """);
    var args = new ArrayList<>();
    args.add(period);
    args.add(auditStatus);
    appendCityScope(sql, args, cityScope, "s");
    sql.append(") audit_points");
    return number(sql.toString(), args);
  }

  private long countPendingAudit(String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT COUNT(*)
              FROM (
                    SELECT DISTINCT s.billing_point_code, s.data_period, s.city_code
                      FROM billing_point_snapshot s
                      LEFT JOIN audit_result a
                        ON a.billing_point_code = s.billing_point_code
                       AND a.data_period = s.data_period
                       AND a.city_code = s.city_code
                     WHERE s.data_period = ?
                       AND (a.id IS NULL OR a.audit_status IN ('PENDING_REVIEW', 'NOT_APPLICABLE'))
                   """);
    var args = new ArrayList<>();
    args.add(period);
    appendCityScope(sql, args, cityScope, "s");
    sql.append(") pending_points");
    return number(sql.toString(), args);
  }

  private long countPendingReports(String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT COUNT(*)
              FROM (
                    SELECT DISTINCT s.billing_point_code, s.data_period, s.city_code
                      FROM billing_point_snapshot s
                      JOIN audit_result a
                        ON a.billing_point_code = s.billing_point_code
                       AND a.data_period = s.data_period
                       AND a.city_code = s.city_code
                     WHERE s.data_period = ?
                       AND a.audit_status = 'OVER_LIMIT'
                       AND NOT EXISTS (
                             SELECT 1
                               FROM audit_report r
                               JOIN billing_point_snapshot report_snapshot
                                 ON report_snapshot.id = r.billing_point_snapshot_id
                              WHERE report_snapshot.billing_point_code = s.billing_point_code
                                AND report_snapshot.data_period = s.data_period
                                AND report_snapshot.city_code = s.city_code
                           )
                   """);
    var args = new ArrayList<>();
    args.add(period);
    appendCityScope(sql, args, cityScope, "s");
    sql.append(") report_points");
    return number(sql.toString(), args);
  }

  private long countReports(String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT COUNT(*)
              FROM (
                    SELECT DISTINCT s.billing_point_code, s.data_period, s.city_code
                      FROM audit_report r
                      JOIN billing_point_snapshot s ON s.id = r.billing_point_snapshot_id
                     WHERE s.data_period = ?
                   """);
    var args = new ArrayList<>();
    args.add(period);
    appendCityScope(sql, args, cityScope, "s");
    sql.append(") report_points");
    return number(sql.toString(), args);
  }

  private long countDistinctSite(String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT COUNT(DISTINCT NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$."关联资源编码"')), ''))
              FROM billing_point_snapshot s
             WHERE s.data_period = ?
            """);
    var args = new ArrayList<>();
    args.add(period);
    appendCityScope(sql, args, cityScope, "s");
    return number(sql.toString(), args);
  }

  private String lastUpdatedAt(String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT MAX(b.completed_at)
              FROM import_job b
             WHERE b.dataset_type = 'BILLING_POINT'
               AND b.data_period = ?
               AND b.status = 'ACTIVE'
            """);
    var args = new ArrayList<>();
    args.add(period);
    appendCityScope(sql, args, cityScope, "b");
    LocalDateTime value =
        jdbcTemplate.queryForObject(sql.toString(), LocalDateTime.class, args.toArray());
    return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  private List<DashboardSummary.DatasetImportSummary> importSummaries(
      String period, String cityScope) {
    return DATASET_TYPES.stream()
        .map(
            type ->
                new DashboardSummary.DatasetImportSummary(
                    type, activeBatch(type, period, cityScope)))
        .toList();
  }

  private ImportBatchSummary activeBatch(String datasetType, String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT b.public_id, b.dataset_type, b.data_period, b.status, f.original_name,
                   b.created_at, b.completed_at AS activated_at, b.row_count, b.error_count, b.errors_json
              FROM import_job b
              JOIN stored_file f ON f.id = b.source_file_id
             WHERE b.dataset_type = ? AND b.data_period = ? AND b.status = 'ACTIVE'
            """);
    var args = new ArrayList<>();
    args.add(datasetType);
    args.add(period);
    appendCityScope(sql, args, cityScope, "b");
    sql.append(" ORDER BY b.completed_at DESC, b.id DESC LIMIT 1");
    return jdbcTemplate.query(sql.toString(), this::mapImportBatch, args.toArray()).stream()
        .findFirst()
        .orElse(null);
  }

  private List<DashboardSummary.NameCount> districtOverLimitCounts(
      String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$."所属区县"')), ''), '未填写') AS name,
                   COUNT(DISTINCT CONCAT(s.billing_point_code, '|', s.data_period, '|', s.city_code)) AS total
              FROM billing_point_snapshot s
              JOIN audit_result a
                ON a.billing_point_code = s.billing_point_code
               AND a.data_period = s.data_period
               AND a.city_code = s.city_code
             WHERE s.data_period = ?
               AND a.audit_status = 'OVER_LIMIT'
            """);
    var args = new ArrayList<>();
    args.add(period);
    appendCityScope(sql, args, cityScope, "s");
    sql.append(" GROUP BY name ORDER BY total DESC, name ASC LIMIT 8");
    return jdbcTemplate.query(
        sql.toString(),
        (rs, row) -> new DashboardSummary.NameCount(rs.getString("name"), rs.getLong("total")),
        args.toArray());
  }

  private List<DashboardSummary.NameCount> overLimitTypeCounts(String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT COALESCE(NULLIF(a.over_limit_type, ''), '未分类') AS name,
                   COUNT(DISTINCT CONCAT(s.billing_point_code, '|', s.data_period, '|', s.city_code)) AS total
              FROM billing_point_snapshot s
              JOIN audit_result a
                ON a.billing_point_code = s.billing_point_code
               AND a.data_period = s.data_period
               AND a.city_code = s.city_code
             WHERE s.data_period = ?
               AND a.audit_status = 'OVER_LIMIT'
            """);
    var args = new ArrayList<>();
    args.add(period);
    appendCityScope(sql, args, cityScope, "s");
    sql.append(" GROUP BY name ORDER BY total DESC, name ASC LIMIT 8");
    return jdbcTemplate.query(
        sql.toString(),
        (rs, row) ->
            new DashboardSummary.NameCount(
                overLimitTypeLabel(rs.getString("name")), rs.getLong("total")),
        args.toArray());
  }

  private List<DashboardSummary.PendingReportTask> pendingTasks(String period, String cityScope) {
    var sql =
        new StringBuilder(
            """
            SELECT s.public_id, s.billing_point_code, s.billing_point_name, s.data_period,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$."所属区县"')), '') AS county,
                   a.actual_amount, a.over_limit_type, a.max_ratio,
                   a.yoy_result, a.yoy_ratio, a.mom_result, a.mom_ratio,
                   a.rated_result, a.rated_ratio,
                   d.analysis_status AS draft_analysis_status
              FROM billing_point_snapshot s
              JOIN audit_result a
                ON a.billing_point_code = s.billing_point_code
               AND a.data_period = s.data_period
               AND a.city_code = s.city_code
              LEFT JOIN report_draft d
                ON d.billing_point_snapshot_id = s.id
             WHERE s.data_period = ?
               AND a.audit_status = 'OVER_LIMIT'
               AND NOT EXISTS (
                     SELECT 1
                       FROM audit_report r
                       JOIN billing_point_snapshot report_snapshot
                         ON report_snapshot.id = r.billing_point_snapshot_id
                      WHERE report_snapshot.billing_point_code = s.billing_point_code
                        AND report_snapshot.data_period = s.data_period
                        AND report_snapshot.city_code = s.city_code
                   )
            """);
    var args = new ArrayList<>();
    args.add(period);
    appendCityScope(sql, args, cityScope, "s");
    sql.append(" ORDER BY a.max_ratio DESC, s.billing_point_code ASC LIMIT 10");
    return jdbcTemplate.query(sql.toString(), this::mapPendingTask, args.toArray());
  }

  private DashboardSummary.PendingReportTask mapPendingTask(ResultSet rs, int row)
      throws SQLException {
    String code = rs.getString("billing_point_code");
    String name = rs.getString("billing_point_name");
    BigDecimal ratio = rs.getBigDecimal("max_ratio");
    String ratioText =
        ratio == null ? "—" : ratio.stripTrailingZeros() + "%";
    return new DashboardSummary.PendingReportTask(
        rs.getString("public_id"),
        rs.getString("public_id"),
        code + " " + name,
        "稽核超标，需生成报告并人工确认",
        "/reports/drafts",
        ratio != null && ratio.compareTo(BigDecimal.valueOf(30)) >= 0 ? "DANGER" : "WARNING",
        code,
        name,
        valueOr(rs.getString("county"), "—"),
        rs.getString("data_period"),
        decimalString(rs.getBigDecimal("actual_amount")),
        overLimitTypeLabel(valueOr(rs.getString("over_limit_type"), "未分类")),
        ratioText,
        overLimitRatios(rs),
        rs.getString("draft_analysis_status"));
  }

  private ImportBatchSummary mapImportBatch(ResultSet rs, int row) throws SQLException {
    LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
    LocalDateTime activatedAt = rs.getObject("activated_at", LocalDateTime.class);
    return new ImportBatchSummary(
        rs.getString("public_id"),
        rs.getString("dataset_type"),
        rs.getString("data_period"),
        rs.getString("original_name"),
        rs.getString("status"),
        createdAt == null ? null : createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        activatedAt == null ? null : activatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        rs.getInt("row_count"),
        rs.getInt("error_count"),
        List.of());
  }

  private void appendCityScope(
      StringBuilder sql, List<Object> args, String cityScope, String alias) {
    if (cityScope != null) {
      sql.append(" AND ");
      if (alias != null && !alias.isBlank()) {
        sql.append(alias).append(".");
      }
      sql.append("city_code = ?");
      args.add(cityScope);
    }
  }

  private long number(String sql, List<Object> args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
    return value == null ? 0 : value;
  }

  private String decimalString(BigDecimal value) {
    return value == null ? "—" : value.stripTrailingZeros().toPlainString();
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String overLimitTypeLabel(String value) {
    return switch (value) {
      case "ONLY_YOY", "仅同比超标", "同比超标" -> "仅同比超标";
      case "ONLY_MOM", "仅环比超标", "环比超标" -> "仅环比超标";
      case "ONLY_RATED", "仅额定标杆超标", "额定标杆超标", "额定功率超标" -> "仅额定标杆超标";
      case "MULTIPLE", "多项超标" -> "多项超标";
      case "NONE", "未超标" -> "未超标";
      default -> valueOr(value, "未分类");
    };
  }

  private List<DashboardSummary.OverLimitRatio> overLimitRatios(ResultSet rs) throws SQLException {
    var ratios = new ArrayList<DashboardSummary.OverLimitRatio>();
    addOverLimitRatio(ratios, rs, "yoy_result", "yoy_ratio", "YOY", "同比");
    addOverLimitRatio(ratios, rs, "mom_result", "mom_ratio", "MOM", "环比");
    addOverLimitRatio(ratios, rs, "rated_result", "rated_ratio", "RATED", "额定标杆");
    return ratios;
  }

  private void addOverLimitRatio(
      List<DashboardSummary.OverLimitRatio> ratios,
      ResultSet rs,
      String resultColumn,
      String ratioColumn,
      String type,
      String label)
      throws SQLException {
    if ("OVER_LIMIT".equals(rs.getString(resultColumn))) {
      ratios.add(new DashboardSummary.OverLimitRatio(type, label, decimalString(rs.getBigDecimal(ratioColumn))));
    }
  }

  public record ImportBatchSummary(
      String id,
      String datasetType,
      String period,
      String fileName,
      String status,
      String createdAt,
      String completedAt,
      int rowCount,
      int errorCount,
      List<Object> errors) {}
}
