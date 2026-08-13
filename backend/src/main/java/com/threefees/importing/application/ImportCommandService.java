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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
  private final TabularFileReader tabularFileReader;
  private final ImportRowMapper importRowMapper;
  private final ObjectMapper objectMapper;

  public ImportCommandService(
      StoredFileService storedFileService,
      BusinessTaskRepository taskRepository,
      ImportBatchRepository batchRepository,
      CityQueryService cityQueryService,
      TabularFileReader tabularFileReader,
      ImportRowMapper importRowMapper,
      ObjectMapper objectMapper) {
    this.storedFileService = storedFileService;
    this.taskRepository = taskRepository;
    this.batchRepository = batchRepository;
    this.cityQueryService = cityQueryService;
    this.tabularFileReader = tabularFileReader;
    this.importRowMapper = importRowMapper;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public List<ImportBatch> submit(
      DatasetType datasetType,
      String fallbackPeriod,
      String requestedCityCode,
      MultipartFile file,
      String idempotencyKey,
      CurrentUser actor) {
    validateFallbackPeriod(fallbackPeriod);
    String cityConstraint = cityConstraint(actor, requestedCityCode);
    String normalizedKey =
        idempotencyKey == null || idempotencyKey.isBlank()
            ? UUID.randomUUID().toString()
            : idempotencyKey;
    if (normalizedKey.length() < 8 || normalizedKey.length() > 128) {
      throw new BusinessRuleException(
          "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key length must be 8 to 128");
    }

    byte[] bytes = readUploadBytes(file);
    TabularData data = tabularFileReader.read(bytes, file.getOriginalFilename());
    List<ImportRowGroup> groups =
        importRowMapper.mapAuto(datasetType, cityConstraint, fallbackPeriod, data);
    if (groups.isEmpty()) {
      throw new BusinessRuleException("IMPORT_DATA_EMPTY", "No importable rows found");
    }

    var storedFile =
        storedFileService.storeUpload(
            file, Set.of("xlsx", "xls", "csv"), "IMPORT_SOURCE", actor.username());
    registerRollbackCleanup(storedFile);

    var created = new ArrayList<ImportBatch>();
    for (ImportRowGroup group : groups) {
      String businessKey =
          "IMPORT:"
              + datasetType
              + ":"
              + group.cityCode()
              + ":"
              + group.period()
              + ":"
              + digest(normalizedKey);
      var existing = taskRepository.findByTypeAndBusinessKey(TaskType.IMPORT, businessKey);
      if (existing.isPresent()) {
        String batchId = readPayloadBatchId(existing.orElseThrow().payloadJson());
        created.add(
            batchRepository
                .findByPublicId(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("import batch")));
        continue;
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
          created.add(
              batchRepository
                  .findByPublicId(concurrentBatchId)
                  .orElseThrow(() -> new ResourceNotFoundException("import batch")));
          continue;
        }
        throw exception;
      }
      created.add(
          batchRepository.create(
              batchPublicId,
              datasetType,
              group.period(),
              group.cityCode(),
              storedFile.id(),
              task.publicId(),
              actor.username()));
    }
    return List.copyOf(created);
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

  private String cityConstraint(CurrentUser actor, String requestedCityCode) {
    if (!actor.roles().contains(Role.SUPER_ADMIN)) {
      if (requestedCityCode != null
          && !requestedCityCode.isBlank()
          && !requestedCityCode.equals(actor.cityCode())) {
        throw new org.springframework.security.access.AccessDeniedException("City scope mismatch");
      }
      return actor.cityCode();
    }
    if (requestedCityCode == null || requestedCityCode.isBlank()) {
      return null;
    }
    boolean known =
        cityQueryService.findAll().stream().anyMatch(city -> city.code().equals(requestedCityCode));
    if (!known) {
      throw new BusinessRuleException("CITY_UNKNOWN", "Unknown city code");
    }
    return requestedCityCode;
  }

  private void validateFallbackPeriod(String period) {
    if (period == null || period.isBlank()) {
      return;
    }
    try {
      java.time.YearMonth.parse(period);
    } catch (java.time.format.DateTimeParseException exception) {
      throw new BusinessRuleException("PERIOD_INVALID", "Period must use YYYY-MM");
    }
  }

  private byte[] readUploadBytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException exception) {
      throw new BusinessRuleException("FILE_READ_FAILED", "Unable to read upload file");
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
