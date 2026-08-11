package com.threefees.importing.application;

import com.threefees.file.application.StoredFileService;
import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.organization.application.CityQueryService;
import com.threefees.task.application.BusinessTaskRepository;
import com.threefees.task.domain.TaskType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ImportCommandService {

  private final StoredFileService storedFileService;
  private final BusinessTaskRepository taskRepository;
  private final ImportBatchRepository batchRepository;
  private final CityQueryService cityQueryService;
  private final ObjectMapper objectMapper;

  public ImportCommandService(
      StoredFileService storedFileService,
      BusinessTaskRepository taskRepository,
      ImportBatchRepository batchRepository,
      CityQueryService cityQueryService,
      ObjectMapper objectMapper) {
    this.storedFileService = storedFileService;
    this.taskRepository = taskRepository;
    this.batchRepository = batchRepository;
    this.cityQueryService = cityQueryService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public ImportBatch submit(
      DatasetType datasetType,
      String period,
      String requestedCityCode,
      MultipartFile file,
      String idempotencyKey,
      CurrentUser actor) {
    validatePeriod(period);
    String cityCode = cityScope(actor, requestedCityCode);
    String normalizedKey =
        idempotencyKey == null || idempotencyKey.isBlank()
            ? UUID.randomUUID().toString()
            : idempotencyKey;
    if (normalizedKey.length() < 8 || normalizedKey.length() > 128) {
      throw new BusinessRuleException("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 长度必须为 8 至 128");
    }
    String businessKey =
        "IMPORT:" + datasetType + ":" + cityCode + ":" + period + ":" + digest(normalizedKey);
    var existing = taskRepository.findByTypeAndBusinessKey(TaskType.IMPORT, businessKey);
    if (existing.isPresent()) {
      String batchId = readPayloadBatchId(existing.orElseThrow().payloadJson());
      return batchRepository
          .findByPublicId(batchId)
          .orElseThrow(() -> new ResourceNotFoundException("导入批次"));
    }

    String batchPublicId = UUID.randomUUID().toString();
    String payload = writeJson(Map.of("batchId", batchPublicId));
    com.threefees.task.domain.BusinessTask task;
    try {
      task = taskRepository.create(TaskType.IMPORT, businessKey, payload, actor.username(), 3);
    } catch (DuplicateKeyException exception) {
      var concurrent = taskRepository.findByTypeAndBusinessKey(TaskType.IMPORT, businessKey);
      if (concurrent.isPresent()) {
        String concurrentBatchId = readPayloadBatchId(concurrent.orElseThrow().payloadJson());
        return batchRepository
            .findByPublicId(concurrentBatchId)
            .orElseThrow(() -> new ResourceNotFoundException("导入批次"));
      }
      throw exception;
    }
    var storedFile =
        storedFileService.storeUpload(
            file, Set.of("xlsx", "xls", "csv"), "IMPORT_SOURCE", actor.username());
    registerRollbackCleanup(storedFile);
    return batchRepository.create(
        batchPublicId,
        datasetType,
        period,
        cityCode,
        storedFile.id(),
        task.publicId(),
        actor.username());
  }

  private void registerRollbackCleanup(com.threefees.file.domain.StoredFile storedFile) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
              storedFileService.deletePhysical(storedFile);
            }
          }
        });
  }

  private String cityScope(CurrentUser actor, String requestedCityCode) {
    if (!actor.roles().contains(Role.SUPER_ADMIN)) {
      if (requestedCityCode != null
          && !requestedCityCode.isBlank()
          && !requestedCityCode.equals(actor.cityCode())) {
        throw new org.springframework.security.access.AccessDeniedException("City scope mismatch");
      }
      return actor.cityCode();
    }
    if (requestedCityCode == null || requestedCityCode.isBlank()) {
      throw new BusinessRuleException("CITY_REQUIRED", "超级管理员导入时必须选择地市");
    }
    boolean known =
        cityQueryService.findAll().stream().anyMatch(city -> city.code().equals(requestedCityCode));
    if (!known) {
      throw new BusinessRuleException("CITY_UNKNOWN", "地市编码不存在");
    }
    return requestedCityCode;
  }

  private void validatePeriod(String period) {
    try {
      YearMonth.parse(period);
    } catch (DateTimeParseException exception) {
      throw new BusinessRuleException("PERIOD_INVALID", "账期必须使用 YYYY-MM 格式");
    }
  }

  private String digest(String value) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, 24);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Import task payload could not be serialized", exception);
    }
  }

  private String readPayloadBatchId(String json) {
    try {
      return objectMapper.readTree(json).path("batchId").asText();
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted import task payload is invalid", exception);
    }
  }
}
