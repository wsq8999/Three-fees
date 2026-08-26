package com.threefees.importing.infrastructure.persistence;

import com.threefees.importing.application.ImportBatchRepository;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportBatchStatus;
import com.threefees.importing.domain.ImportError;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcImportBatchRepository implements ImportBatchRepository {

  private static final TypeReference<List<ImportError>> ERROR_LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcImportBatchRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public ImportBatch create(
      String publicId,
      DatasetType datasetType,
      String period,
      LocalDate periodStart,
      LocalDate periodEnd,
      String cityCode,
      long sourceFileId,
      String taskPublicId,
      String actor) {
    var keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  INSERT INTO import_job
                    (public_id, dataset_type, data_period, period_start, period_end, city_code, status, source_file_id,
                     task_public_id, row_count, error_count, errors_json, created_by, updated_by)
                  VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, 0, 0, '[]', ?, ?)
                  """,
                  new String[] {"id"});
          statement.setString(1, publicId);
          statement.setString(2, datasetType.name());
          statement.setString(3, period);
          statement.setDate(4, periodStart == null ? null : Date.valueOf(periodStart));
          statement.setDate(5, periodEnd == null ? null : Date.valueOf(periodEnd));
          statement.setString(6, cityCode);
          statement.setLong(7, sourceFileId);
          statement.setString(8, taskPublicId);
          statement.setString(9, actor);
          statement.setString(10, actor);
          return statement;
        },
        keyHolder);
    Number key = keyHolder.getKey();
    return findById(key == null ? -1 : key.longValue()).orElseThrow();
  }

  @Override
  public Optional<ImportBatch> findByPublicId(String publicId) {
    return query("WHERE public_id = ?", publicId).stream().findFirst();
  }

  @Override
  public Optional<ImportBatch> findById(long id) {
    return query("WHERE id = ?", id).stream().findFirst();
  }

  @Override
  public List<ImportBatch> findByTaskPublicId(String taskPublicId) {
    return query("WHERE task_public_id = ? ORDER BY data_period ASC, id ASC", taskPublicId);
  }

  @Override
  public List<ImportBatch> findPage(
      DatasetType datasetType, String period, String cityCode, int offset, int limit) {
    var sql = new StringBuilder("WHERE 1 = 1");
    var arguments = new java.util.ArrayList<>();
    appendFilters(sql, arguments, datasetType, period, cityCode);
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
    arguments.add(limit);
    arguments.add(offset);
    return query(sql.toString(), arguments.toArray());
  }

  @Override
  public Optional<ImportBatch> findLatestBatch(DatasetType datasetType, String cityCode) {
    var sql = new StringBuilder("WHERE dataset_type = ?");
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(datasetType.name());
    if (cityCode != null && !cityCode.isBlank()) {
      sql.append(" AND city_code = ?");
      arguments.add(cityCode);
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT 1");
    return query(sql.toString(), arguments.toArray()).stream().findFirst();
  }

  @Override
  public List<ImportBatch> findLatestSession(
      DatasetType datasetType, String cityCode, LocalDateTime sessionStartedAt) {
    var latestSql =
        new StringBuilder(
            """
            SELECT source_file_id
              FROM import_job
             WHERE dataset_type = ? AND created_at >= ?
            """);
    var latestArguments = new java.util.ArrayList<Object>();
    latestArguments.add(datasetType.name());
    latestArguments.add(sessionStartedAt);
    if (cityCode != null && !cityCode.isBlank()) {
      latestSql.append(" AND city_code = ?");
      latestArguments.add(cityCode);
    }
    latestSql.append(" ORDER BY created_at DESC, id DESC LIMIT 1");
    List<Long> sourceFileIds =
        jdbcTemplate.queryForList(latestSql.toString(), Long.class, latestArguments.toArray());
    if (sourceFileIds.isEmpty()) {
      return List.of();
    }

    var sessionSql = new StringBuilder("WHERE dataset_type = ? AND source_file_id = ?");
    var sessionArguments = new java.util.ArrayList<Object>();
    sessionArguments.add(datasetType.name());
    sessionArguments.add(sourceFileIds.getFirst());
    if (cityCode != null && !cityCode.isBlank()) {
      sessionSql.append(" AND city_code = ?");
      sessionArguments.add(cityCode);
    }
    sessionSql.append(" ORDER BY data_period ASC, id ASC");
    return query(sessionSql.toString(), sessionArguments.toArray());
  }

  @Override
  public long count(DatasetType datasetType, String period, String cityCode) {
    var sql = new StringBuilder("SELECT COUNT(*) FROM import_job WHERE 1 = 1");
    var arguments = new java.util.ArrayList<>();
    appendFilters(sql, arguments, datasetType, period, cityCode);
    Long result = jdbcTemplate.queryForObject(sql.toString(), Long.class, arguments.toArray());
    return result == null ? 0 : result;
  }

  @Override
  public boolean prerequisitesActive(ImportBatch batch) {
    for (DatasetType prerequisite : batch.datasetType().prerequisites()) {
      boolean ready =
          switch (prerequisite) {
            case BILLING_POINT -> hasKnownBillingPoints(batch.cityCode());
            case PAYMENT -> hasRows("payment_detail", batch.cityCode(), batch.period());
            case METER_READING -> hasRows("meter_reading", batch.cityCode(), batch.period());
            case BENCHMARK -> hasRows("benchmark_value", batch.cityCode(), batch.period());
          };
      if (!ready) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean allDatasetsActive(String period, String cityCode) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(DISTINCT dataset_type)
              FROM import_job
             WHERE data_period = ? AND city_code = ? AND status = 'ACTIVE'
               AND dataset_type IN ('BILLING_POINT', 'PAYMENT', 'METER_READING', 'BENCHMARK')
            """,
            Integer.class,
            period,
            cityCode);
    return count != null && count == DatasetType.values().length;
  }

  @Override
  public Optional<String> findActiveCityForPayment(
      String period, String billingPointCode, String paymentCode) {
    if (period == null
        || period.isBlank()
        || billingPointCode == null
        || billingPointCode.isBlank()
        || paymentCode == null
        || paymentCode.isBlank()) {
      return Optional.empty();
    }
    return jdbcTemplate
        .queryForList(
            """
            SELECT DISTINCT city_code
              FROM payment_detail
             WHERE data_period = ? AND billing_point_code = ? AND payment_bill_code = ?
             ORDER BY city_code
             LIMIT 2
            """,
            String.class,
            period,
            billingPointCode,
            paymentCode)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<String> findActiveCityForBillingPoint(String period, String billingPointCode) {
    if (billingPointCode == null || billingPointCode.isBlank()) {
      return Optional.empty();
    }
    if (period != null && !period.isBlank()) {
      Optional<String> samePeriod =
          jdbcTemplate
              .queryForList(
                  """
                  SELECT DISTINCT city_code
                    FROM billing_point_snapshot
                   WHERE data_period = ? AND billing_point_code = ?
                   ORDER BY city_code
                   LIMIT 2
                  """,
                  String.class,
                  period,
                  billingPointCode)
              .stream()
              .findFirst();
      if (samePeriod.isPresent()) {
        return samePeriod;
      }
    }
    return jdbcTemplate
        .queryForList(
            """
            SELECT city_code
              FROM billing_point_master
             WHERE billing_point_code = ?
            UNION
            SELECT city_code
              FROM billing_point_snapshot
             WHERE billing_point_code = ?
            ORDER BY city_code
            LIMIT 2
            """,
            String.class,
            billingPointCode,
            billingPointCode)
        .stream()
        .findFirst();
  }

  @Override
  public void markProcessing(long id) {
    jdbcTemplate.update(
        """
        UPDATE import_job
           SET status = 'PROCESSING', row_count = 0, error_count = 0, errors_json = '[]',
               completed_at = NULL, updated_at = CURRENT_TIMESTAMP(3),
               updated_by = 'WORKER', version = version + 1
         WHERE id = ? AND status IN ('QUEUED', 'FAILED')
        """,
        id);
  }

  @Override
  public void markPreflightCompleted(long id, int rowCount) {
    jdbcTemplate.update(
        """
        UPDATE import_job
           SET row_count = ?, updated_at = CURRENT_TIMESTAMP(3),
               updated_by = 'WORKER', version = version + 1
         WHERE id = ? AND status = 'PROCESSING'
        """,
        rowCount,
        id);
  }

  @Override
  public void markSucceeded(ImportBatch batch, int rowCount) {
    jdbcTemplate.update(
        """
        UPDATE import_job
           SET status = 'ACTIVE', row_count = ?, error_count = 0, errors_json = '[]',
               completed_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3),
               updated_by = 'WORKER', version = version + 1
         WHERE id = ?
        """,
        rowCount,
        batch.id());
  }

  @Override
  public void markFailed(long id, List<ImportError> errors) {
    jdbcTemplate.update(
        """
        UPDATE import_job
           SET status = 'FAILED', error_count = ?, errors_json = ?,
               completed_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3),
               updated_by = 'WORKER', version = version + 1
         WHERE id = ?
        """,
        errors.size(),
        writeJson(errors),
        id);
  }

  private void appendFilters(
      StringBuilder sql,
      List<Object> arguments,
      DatasetType datasetType,
      String period,
      String cityCode) {
    if (datasetType != null) {
      sql.append(" AND dataset_type = ?");
      arguments.add(datasetType.name());
    }
    if (period != null && !period.isBlank()) {
      sql.append(" AND data_period = ?");
      arguments.add(period);
    }
    if (cityCode != null && !cityCode.isBlank()) {
      sql.append(" AND city_code = ?");
      arguments.add(cityCode);
    }
  }

  private List<ImportBatch> query(String predicate, Object... arguments) {
    return jdbcTemplate.query(
        """
        SELECT id, public_id, dataset_type, data_period, period_start, period_end, city_code, status, source_file_id,
               task_public_id, row_count, error_count, errors_json, completed_at,
               created_at, created_by, updated_at, version
          FROM import_job
        """
            + predicate,
        (resultSet, rowNumber) ->
            new ImportBatch(
                resultSet.getLong("id"),
                resultSet.getString("public_id"),
                DatasetType.valueOf(resultSet.getString("dataset_type")),
                resultSet.getString("data_period"),
                resultSet.getObject("period_start", LocalDate.class),
                resultSet.getObject("period_end", LocalDate.class),
                resultSet.getString("city_code"),
                ImportBatchStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("source_file_id"),
                resultSet.getString("task_public_id"),
                resultSet.getInt("row_count"),
                resultSet.getInt("error_count"),
                readErrors(resultSet.getString("errors_json")),
                resultSet.getObject("completed_at", LocalDateTime.class),
                resultSet.getObject("created_at", LocalDateTime.class),
                resultSet.getString("created_by"),
                resultSet.getObject("updated_at", LocalDateTime.class),
                resultSet.getLong("version")),
        arguments);
  }

  private boolean hasKnownBillingPoints(String cityCode) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM (
                    SELECT billing_point_code
                      FROM billing_point_master
                     WHERE city_code = ?
                    UNION
                    SELECT billing_point_code
                      FROM billing_point_snapshot
                     WHERE city_code = ?
                   ) known_points
            """,
            Integer.class,
            cityCode,
            cityCode);
    return count != null && count > 0;
  }

  private boolean hasRows(String tableName, String cityCode, String period) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + tableName + " WHERE city_code = ? AND data_period = ?",
            Integer.class,
            cityCode,
            period);
    return count != null && count > 0;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Import job state could not be serialized", exception);
    }
  }

  private List<ImportError> readErrors(String json) {
    try {
      return objectMapper.readValue(json, ERROR_LIST);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted import job errors are invalid", exception);
    }
  }
}
