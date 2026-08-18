package com.threefees.report.application;

import com.threefees.file.application.StoredFileService;
import com.threefees.file.domain.StoredFile;
import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import com.threefees.task.application.BusinessTaskRepository;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class HistoricalReportService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final StoredFileService storedFileService;
  private final BusinessTaskRepository taskRepository;

  public HistoricalReportService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      StoredFileService storedFileService,
      BusinessTaskRepository taskRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.storedFileService = storedFileService;
    this.taskRepository = taskRepository;
  }

  @Transactional
  public HistoricalImport submit(
      String billingPointPeriodId, MultipartFile file, String idempotencyKey, CurrentUser actor) {
    if (billingPointPeriodId == null || billingPointPeriodId.isBlank()) {
      throw new BusinessRuleException(
          "HISTORICAL_REPORT_INPUT_INVALID", "billingPointPeriodId is required");
    }
    Candidate candidate = candidate(billingPointPeriodId);
    return submit(candidate, billingPointPeriodId, file, idempotencyKey, actor);
  }

  @Transactional
  public HistoricalImport submitByBillingPointPeriod(
      String billingPointCode,
      String cityCode,
      String period,
      MultipartFile file,
      String idempotencyKey,
      CurrentUser actor) {
    if (billingPointCode == null
        || billingPointCode.isBlank()
        || period == null
        || period.isBlank()) {
      throw new BusinessRuleException(
          "HISTORICAL_REPORT_INPUT_INVALID", "billingPointCode and period are required");
    }
    Candidate candidate = candidate(billingPointCode.trim(), cityCode, period.trim(), actor);
    String referenceCity = candidate.cityCode() == null ? "" : candidate.cityCode();
    return submit(
        candidate,
        billingPointCode.trim() + ":" + period.trim() + ":" + referenceCity,
        file,
        idempotencyKey,
        actor);
  }

  private HistoricalImport submit(
      Candidate candidate,
      String businessReference,
      MultipartFile file,
      String idempotencyKey,
      CurrentUser actor) {
    requireScope(actor, candidate.cityCode());
    HistoricalImport prior = findBySnapshot(candidate.snapshotId());
    if (prior != null) {
      if ("FAILED".equals(prior.status())) {
        return retryFailedImport(businessReference, prior.id(), file, idempotencyKey, actor);
      }
      return prior;
    }
    if (!candidate.eligible()) {
      throw new BusinessRuleException(
          "HISTORICAL_REPORT_NOT_ELIGIBLE",
          "Historical report already exists or period is not eligible");
    }
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    String businessKey = "HISTORY:" + businessReference + ":" + digest(normalizedKey);
    var existingTask =
        taskRepository.findByTypeAndBusinessKey(TaskType.HISTORICAL_REPORT_IMPORT, businessKey);
    if (existingTask.isPresent()) {
      return findByTask(existingTask.orElseThrow().publicId());
    }
    String importId = UUID.randomUUID().toString();
    BusinessTask task =
        taskRepository.create(
            TaskType.HISTORICAL_REPORT_IMPORT,
            businessKey,
            writeJson(Map.of("historicalImportId", importId)),
            actor.username(),
            3);
    StoredFile source =
        storedFileService.storeUpload(
            file, Set.of("doc", "docx"), "HISTORICAL_REPORT_WORD", actor.username());
    registerRollbackCleanup(source);
    try {
      jdbcTemplate.update(
          """
          INSERT INTO historical_report_import
            (public_id, billing_point_snapshot_id, source_word_file_id, task_public_id,
             status, created_by, updated_by)
          VALUES (?, ?, ?, ?, 'QUEUED', ?, ?)
          """,
          importId,
          candidate.snapshotId(),
          source.id(),
          task.publicId(),
          actor.username(),
          actor.username());
    } catch (DuplicateKeyException exception) {
      HistoricalImport concurrent = findBySnapshot(candidate.snapshotId());
      if (concurrent != null) {
        return concurrent;
      }
      throw exception;
    }
    return find(importId, actor);
  }

  private HistoricalImport retryFailedImport(
      String billingPointPeriodId,
      String importId,
      MultipartFile file,
      String idempotencyKey,
      CurrentUser actor) {
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    String businessKey =
        "HISTORY_RETRY:" + billingPointPeriodId + ":" + importId + ":" + digest(normalizedKey);
    var existingTask =
        taskRepository.findByTypeAndBusinessKey(TaskType.HISTORICAL_REPORT_IMPORT, businessKey);
    if (existingTask.isPresent()) {
      return findByTask(existingTask.orElseThrow().publicId());
    }
    BusinessTask task =
        taskRepository.create(
            TaskType.HISTORICAL_REPORT_IMPORT,
            businessKey,
            writeJson(Map.of("historicalImportId", importId)),
            actor.username(),
            3);
    StoredFile source =
        storedFileService.storeUpload(
            file, Set.of("doc", "docx"), "HISTORICAL_REPORT_WORD", actor.username());
    registerRollbackCleanup(source);
    int updated =
        jdbcTemplate.update(
            """
            UPDATE historical_report_import
               SET source_word_file_id=?, task_public_id=?, status='QUEUED',
                   error_code=NULL, report_public_id=NULL,
                   updated_at=CURRENT_TIMESTAMP(3), updated_by=?, version=version+1
             WHERE public_id=? AND status='FAILED'
            """,
            source.id(),
            task.publicId(),
            actor.username(),
            importId);
    if (updated != 1) {
      storedFileService.deletePhysical(source);
    }
    return find(importId, actor);
  }

  private String normalizeIdempotencyKey(String idempotencyKey) {
    String normalizedKey =
        idempotencyKey == null || idempotencyKey.isBlank()
            ? UUID.randomUUID().toString()
            : idempotencyKey.trim();
    if (normalizedKey.length() < 8 || normalizedKey.length() > 128) {
      throw new BusinessRuleException("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 长度必须为 8 至 128");
    }
    return normalizedKey;
  }

  @Transactional(readOnly = true)
  public HistoricalImport find(String publicId, CurrentUser actor) {
    HistoricalImport value = findOne("h.public_id = ?", publicId);
    requireScope(actor, value.cityCode());
    return value;
  }

  @Transactional(readOnly = true)
  public HistoricalTaskInput taskInput(String publicId) {
    return jdbcTemplate
        .query(
            """
            SELECT h.id, h.public_id, h.billing_point_snapshot_id, h.status,
                   h.source_word_file_id, f.public_id AS source_file_public_id,
                   f.original_name, s.billing_point_code, s.billing_point_name,
                   s.city_code, s.data_period
              FROM historical_report_import h
              JOIN stored_file f ON f.id = h.source_word_file_id
              JOIN billing_point_snapshot s ON s.id = h.billing_point_snapshot_id
             WHERE h.public_id = ?
            """,
            (rs, row) ->
                new HistoricalTaskInput(
                    rs.getLong("id"),
                    rs.getString("public_id"),
                    rs.getLong("billing_point_snapshot_id"),
                    rs.getLong("source_word_file_id"),
                    rs.getString("source_file_public_id"),
                    rs.getString("original_name"),
                    rs.getString("billing_point_code"),
                    rs.getString("billing_point_name"),
                    rs.getString("city_code"),
                    rs.getString("data_period"),
                    rs.getString("status")),
            publicId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("历史报告导入任务"));
  }

  @Transactional
  public void markProcessing(long id, String actor) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE historical_report_import
               SET status='PROCESSING', error_code=NULL,
                   updated_at=CURRENT_TIMESTAMP(3), updated_by=?, version=version+1
             WHERE id=? AND status IN ('QUEUED','FAILED','PROCESSING')
            """,
            actor,
            id);
    if (updated != 1) {
      throw new ResourceConflictException("HISTORICAL_IMPORT_STATE_INVALID", "历史报告导入状态不可处理");
    }
  }

  @Transactional
  public void markFailed(long id, String code, String actor) {
    jdbcTemplate.update(
        """
        UPDATE historical_report_import
           SET status='FAILED', error_code=?,
               updated_at=CURRENT_TIMESTAMP(3), updated_by=?, version=version+1
         WHERE id=? AND status <> 'SUCCEEDED'
        """,
        code,
        actor,
        id);
  }

  private Candidate candidate(String publicId) {
    return jdbcTemplate
        .query(
            """
            SELECT s.id, s.city_code,
                   CASE WHEN r.id IS NULL THEN TRUE ELSE FALSE END AS eligible
              FROM billing_point_snapshot s
              LEFT JOIN audit_report r ON r.billing_point_snapshot_id=s.id
             WHERE s.public_id=?
            """,
            (rs, row) ->
                new Candidate(
                    rs.getLong("id"), rs.getString("city_code"), rs.getBoolean("eligible")),
            publicId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("报账点账期"));
  }

  private Candidate candidate(
      String billingPointCode, String cityCode, String period, CurrentUser actor) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT s.id, s.city_code,
                   CASE WHEN r.id IS NULL THEN TRUE ELSE FALSE END AS eligible
              FROM billing_point_snapshot s
              LEFT JOIN audit_report r ON r.billing_point_snapshot_id=s.id
             WHERE s.billing_point_code=? AND s.data_period=?
            """);
    var args = new java.util.ArrayList<Object>();
    args.add(billingPointCode);
    args.add(period);
    String scopedCity = scopeCity(actor, cityCode);
    if (scopedCity != null && !scopedCity.isBlank()) {
      sql.append(" AND s.city_code=?");
      args.add(scopedCity);
    }
    List<Candidate> candidates =
        jdbcTemplate.query(
            sql + " ORDER BY s.id DESC LIMIT 2",
            (rs, row) ->
                new Candidate(
                    rs.getLong("id"), rs.getString("city_code"), rs.getBoolean("eligible")),
            args.toArray());
    if (candidates.isEmpty()) {
      throw new ResourceNotFoundException("报账点账期不存在");
    }
    if (candidates.size() > 1) {
      throw new ResourceConflictException(
          "HISTORICAL_REPORT_PERIOD_AMBIGUOUS",
          "Billing point code and period matched multiple snapshots");
    }
    return candidates.getFirst();
  }

  private String scopeCity(CurrentUser actor, String requestedCityCode) {
    if (actor == null || actor.roles().contains(Role.SUPER_ADMIN)) {
      return requestedCityCode;
    }
    String actorCity = actor.cityCode();
    if (actorCity == null || actorCity.isBlank()) {
      throw new ResourceNotFoundException("当前用户未绑定城市");
    }
    if (requestedCityCode != null
        && !requestedCityCode.isBlank()
        && !actorCity.equals(requestedCityCode)) {
      throw new ResourceNotFoundException("报账点账期不存在");
    }
    return actorCity;
  }

  private HistoricalImport findBySnapshot(long snapshotId) {
    return jdbcTemplate
        .query(
            "SELECT h.public_id FROM historical_report_import h WHERE h.billing_point_snapshot_id=?",
            (rs, row) -> rs.getString(1),
            snapshotId)
        .stream()
        .findFirst()
        .map(id -> findOne("h.public_id = ?", id))
        .orElse(null);
  }

  private HistoricalImport findByTask(String taskPublicId) {
    return findOne("h.task_public_id = ?", taskPublicId);
  }

  private HistoricalImport findOne(String predicate, String value) {
    return jdbcTemplate
        .query(
            """
            SELECT h.public_id, h.status, h.error_code, h.report_public_id,
                   h.task_public_id, f.public_id AS source_file_id,
                   s.public_id AS snapshot_public_id, s.billing_point_code,
                   s.billing_point_name, s.city_code, s.data_period,
                   h.created_at, h.updated_at, h.version
              FROM historical_report_import h
              JOIN stored_file f ON f.id=h.source_word_file_id
              JOIN billing_point_snapshot s ON s.id=h.billing_point_snapshot_id
             WHERE
            """
                + predicate,
            (rs, row) ->
                new HistoricalImport(
                    rs.getString("public_id"),
                    rs.getString("snapshot_public_id"),
                    rs.getString("billing_point_code"),
                    rs.getString("billing_point_name"),
                    rs.getString("city_code"),
                    rs.getString("data_period"),
                    rs.getString("source_file_id"),
                    rs.getString("task_public_id"),
                    rs.getString("status"),
                    rs.getString("error_code"),
                    rs.getString("report_public_id"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class),
                    rs.getLong("version")),
            value)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("历史报告导入"));
  }

  private void registerRollbackCleanup(StoredFile file) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
              storedFileService.deletePhysical(file);
            }
          }
        });
  }

  private void requireScope(CurrentUser actor, String cityCode) {
    if (!actor.roles().contains(Role.SUPER_ADMIN) && !actor.cityCode().equals(cityCode)) {
      throw new AccessDeniedException("Historical report is outside city scope");
    }
  }

  private String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 24);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Historical import task could not be serialized", exception);
    }
  }

  private record Candidate(long snapshotId, String cityCode, boolean eligible) {}

  public record HistoricalImport(
      String id,
      String billingPointPeriodId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String period,
      String sourceFileId,
      String taskId,
      String status,
      String errorCode,
      String reportId,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long version) {}

  public record HistoricalTaskInput(
      long id,
      String publicId,
      long snapshotId,
      long sourceWordFileId,
      String sourceFilePublicId,
      String originalName,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String period,
      String status) {}
}
