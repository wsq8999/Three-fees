package com.threefees.importing.infrastructure.persistence;

import com.threefees.importing.application.ImportBatchRepository;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportBatchStatus;
import com.threefees.importing.domain.ImportError;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
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
                  INSERT INTO import_batch
                    (public_id, dataset_type, data_period, city_code, status, source_file_id,
                     task_public_id, errors_json, created_by, updated_by)
                  VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, '[]', ?, ?)
                  """,
                  new String[] {"id"});
          statement.setString(1, publicId);
          statement.setString(2, datasetType.name());
          statement.setString(3, period);
          statement.setString(4, cityCode);
          statement.setLong(5, sourceFileId);
          statement.setString(6, taskPublicId);
          statement.setString(7, actor);
          statement.setString(8, actor);
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
  public long count(DatasetType datasetType, String period, String cityCode) {
    var sql = new StringBuilder("SELECT COUNT(*) FROM import_batch WHERE 1 = 1");
    var arguments = new java.util.ArrayList<>();
    appendFilters(sql, arguments, datasetType, period, cityCode);
    Long result = jdbcTemplate.queryForObject(sql.toString(), Long.class, arguments.toArray());
    return result == null ? 0 : result;
  }

  @Override
  public boolean prerequisitesActive(ImportBatch batch) {
    for (DatasetType prerequisite : batch.datasetType().prerequisites()) {
      Integer count =
          jdbcTemplate.queryForObject(
              """
              SELECT COUNT(*) FROM import_batch
               WHERE dataset_type = ? AND data_period = ? AND city_code = ? AND status = 'ACTIVE'
              """,
              Integer.class,
              prerequisite.name(),
              batch.period(),
              batch.cityCode());
      if (count == null || count == 0) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void markProcessing(long id) {
    jdbcTemplate.update(
        """
        UPDATE import_batch
           SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP(3),
               updated_by = 'WORKER', version = version + 1
         WHERE id = ? AND status IN ('QUEUED', 'FAILED')
        """,
        id);
  }

  @Override
  @Transactional
  public void replaceRows(long batchId, List<ImportedRow> rows) {
    jdbcTemplate.update("DELETE FROM imported_record WHERE batch_id = ?", batchId);
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO imported_record
          (batch_id, dataset_type, data_period, city_code, billing_point_code,
           payment_code, meter_code, business_key, source_row, values_json, is_active)
        SELECT b.id, b.dataset_type, b.data_period, ?, ?, ?, ?, ?, ?, ?, FALSE
          FROM import_batch b WHERE b.id = ?
        """,
        rows,
        500,
        (statement, row) -> {
          statement.setString(1, row.cityCode());
          statement.setString(2, row.billingPointCode());
          statement.setString(3, row.paymentCode());
          statement.setString(4, row.meterCode());
          statement.setString(5, row.businessKey());
          statement.setInt(6, row.sourceRow());
          statement.setString(7, row.valuesJson());
          statement.setLong(8, batchId);
        });
  }

  @Override
  @Transactional
  public void activate(ImportBatch batch, List<ImportedRow> rows) {
    jdbcTemplate.update(
        """
        UPDATE imported_record SET is_active = FALSE
         WHERE batch_id IN (
           SELECT id FROM import_batch
            WHERE dataset_type = ? AND data_period = ? AND city_code = ? AND status = 'ACTIVE'
         )
        """,
        batch.datasetType().name(),
        batch.period(),
        batch.cityCode());
    jdbcTemplate.update(
        """
        UPDATE import_batch
           SET status = 'SUPERSEDED', superseded_at = CURRENT_TIMESTAMP(3),
               updated_at = CURRENT_TIMESTAMP(3), updated_by = 'WORKER', version = version + 1
         WHERE dataset_type = ? AND data_period = ? AND city_code = ?
           AND status = 'ACTIVE' AND id <> ?
        """,
        batch.datasetType().name(),
        batch.period(),
        batch.cityCode(),
        batch.id());
    jdbcTemplate.update(
        "UPDATE imported_record SET is_active = TRUE WHERE batch_id = ?", batch.id());
    jdbcTemplate.update(
        """
        UPDATE import_batch
           SET status = 'ACTIVE', row_count = ?, error_count = 0, errors_json = '[]',
               activated_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3),
               updated_by = 'WORKER', version = version + 1
         WHERE id = ?
        """,
        rows.size(),
        batch.id());
    if (batch.datasetType() == DatasetType.BILLING_POINT) {
      for (ImportedRow row : rows) {
        upsertSnapshot(batch, row);
        upsertMaster(batch, row);
      }
    }
  }

  @Override
  public void markFailed(long id, List<ImportError> errors) {
    jdbcTemplate.update(
        """
        UPDATE import_batch
           SET status = 'FAILED', error_count = ?, errors_json = ?,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = 'WORKER', version = version + 1
         WHERE id = ?
        """,
        errors.size(),
        writeJson(errors),
        id);
  }

  private void upsertSnapshot(ImportBatch batch, ImportedRow row) {
    YearMonth month = YearMonth.parse(batch.period());
    int updated =
        jdbcTemplate.update(
            """
            UPDATE billing_point_snapshot
               SET city_code = ?, billing_point_name = ?, period_start = ?, period_end = ?,
                   data_json = ?, source_batch_id = ?, updated_at = CURRENT_TIMESTAMP(3),
                   version = version + 1
             WHERE billing_point_code = ? AND data_period = ?
            """,
            row.cityCode(),
            row.billingPointName(),
            LocalDate.of(month.getYear(), month.getMonth(), 1),
            month.atEndOfMonth(),
            row.valuesJson(),
            batch.id(),
            row.billingPointCode(),
            batch.period());
    if (updated == 0) {
      jdbcTemplate.update(
          """
          INSERT INTO billing_point_snapshot
            (public_id, billing_point_code, city_code, billing_point_name, data_period,
             period_start, period_end, data_json, source_batch_id)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          UUID.randomUUID().toString(),
          row.billingPointCode(),
          row.cityCode(),
          row.billingPointName(),
          batch.period(),
          month.atDay(1),
          month.atEndOfMonth(),
          row.valuesJson(),
          batch.id());
    }
  }

  private void upsertMaster(ImportBatch batch, ImportedRow row) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE billing_point_master
               SET city_code = ?, billing_point_name = ?, current_period = ?, data_json = ?,
                   source_batch_id = ?, updated_at = CURRENT_TIMESTAMP(3), version = version + 1
             WHERE billing_point_code = ? AND current_period <= ?
            """,
            row.cityCode(),
            row.billingPointName(),
            batch.period(),
            row.valuesJson(),
            batch.id(),
            row.billingPointCode(),
            batch.period());
    if (updated == 0) {
      Integer exists =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM billing_point_master WHERE billing_point_code = ?",
              Integer.class,
              row.billingPointCode());
      if (exists != null && exists == 0) {
        jdbcTemplate.update(
            """
            INSERT INTO billing_point_master
              (billing_point_code, city_code, billing_point_name, current_period,
               data_json, source_batch_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            row.billingPointCode(),
            row.cityCode(),
            row.billingPointName(),
            batch.period(),
            row.valuesJson(),
            batch.id());
      }
    }
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
        SELECT id, public_id, dataset_type, data_period, city_code, status, source_file_id,
               task_public_id, row_count, error_count, errors_json, activated_at,
               created_at, created_by, updated_at, version
          FROM import_batch
        """
            + predicate,
        (resultSet, rowNumber) ->
            new ImportBatch(
                resultSet.getLong("id"),
                resultSet.getString("public_id"),
                DatasetType.valueOf(resultSet.getString("dataset_type")),
                resultSet.getString("data_period"),
                resultSet.getString("city_code"),
                ImportBatchStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("source_file_id"),
                resultSet.getString("task_public_id"),
                resultSet.getInt("row_count"),
                resultSet.getInt("error_count"),
                readErrors(resultSet.getString("errors_json")),
                resultSet.getObject("activated_at", LocalDateTime.class),
                resultSet.getObject("created_at", LocalDateTime.class),
                resultSet.getString("created_by"),
                resultSet.getObject("updated_at", LocalDateTime.class),
                resultSet.getLong("version")),
        arguments);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Import state could not be serialized", exception);
    }
  }

  private List<ImportError> readErrors(String json) {
    try {
      return objectMapper.readValue(json, ERROR_LIST);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted import errors are invalid", exception);
    }
  }
}
