package com.threefees.importing.application;

import com.threefees.file.application.StoredFileService;
import com.threefees.importing.domain.DatasetType;
import com.threefees.task.application.TaskExecutionException;
import com.threefees.task.application.TaskProcessor;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExportTaskProcessor implements TaskProcessor {

  private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP =
      new TypeReference<>() {};

  private final ExportJobRepository jobRepository;
  private final FieldCatalogService fieldCatalogService;
  private final StoredFileService storedFileService;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public ExportTaskProcessor(
      ExportJobRepository jobRepository,
      FieldCatalogService fieldCatalogService,
      StoredFileService storedFileService,
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper) {
    this.jobRepository = jobRepository;
    this.fieldCatalogService = fieldCatalogService;
    this.storedFileService = storedFileService;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public TaskType taskType() {
    return TaskType.EXPORT;
  }

  @Override
  public String process(BusinessTask task) {
    String jobId = payloadJobId(task.payloadJson());
    var job = jobRepository.findByPublicId(jobId).orElseThrow();
    jobRepository.markProcessing(job.id());
    try {
      List<String> billingPointCodes = billingPointCodes(job.billingPointIds());
      var files = new LinkedHashMap<String, byte[]>();
      for (DatasetType datasetType : job.datasetTypes()) {
        String filename = job.period() + "-" + datasetType.name().toLowerCase() + ".xlsx";
        files.put(filename, workbook(datasetType, job.period(), job.cityCode(), billingPointCodes));
      }
      byte[] resultBytes;
      String resultName;
      String mediaType;
      if (files.size() == 1) {
        var only = files.entrySet().iterator().next();
        resultName = only.getKey();
        resultBytes = only.getValue();
        mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      } else {
        resultName = job.period() + "-three-fees-export.zip";
        resultBytes = zip(files);
        mediaType = "application/zip";
      }
      var stored =
          storedFileService.storeGenerated(
              resultBytes, resultName, mediaType, "EXPORT_RESULT", job.createdBy());
      jobRepository.markSucceeded(job.id(), stored.id());
      return writeJson(
          Map.of(
              "exportJobId",
              job.publicId(),
              "fileId",
              stored.publicId(),
              "downloadUrl",
              "/api/v1/files/" + stored.publicId()));
    } catch (RuntimeException exception) {
      jobRepository.markFailed(job.id(), "EXPORT_FAILED");
      throw new TaskExecutionException("EXPORT_FAILED", exception.getMessage(), true);
    }
  }

  private byte[] workbook(
      DatasetType datasetType, String period, String cityCode, List<String> billingPointCodes) {
    try (var workbook = new SXSSFWorkbook(100);
        var output = new ByteArrayOutputStream()) {
      workbook.setCompressTempFiles(true);
      var sheet = workbook.createSheet("数据");
      CellStyle textStyle = workbook.createCellStyle();
      textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
      var header = sheet.createRow(0);
      var fields = fieldCatalogService.fields(datasetType);
      for (int index = 0; index < fields.size(); index++) {
        header.createCell(index).setCellValue(fields.get(index).sourceName());
      }
      List<Map<String, String>> rows = rows(datasetType, period, cityCode, billingPointCodes);
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        var excelRow = sheet.createRow(rowIndex + 1);
        Map<String, String> values = rows.get(rowIndex);
        for (int column = 0; column < fields.size(); column++) {
          var cell = excelRow.createCell(column);
          cell.setCellStyle(textStyle);
          String value = values.getOrDefault(fields.get(column).technicalName(), "");
          cell.setCellValue(value.length() > 32767 ? value.substring(0, 32767) : value);
        }
      }
      workbook.write(output);
      workbook.dispose();
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Excel export could not be generated", exception);
    }
  }

  private List<Map<String, String>> rows(
      DatasetType datasetType, String period, String cityCode, List<String> billingPointCodes) {
    if (billingPointCodes.isEmpty()) {
      return List.of();
    }
    String placeholders =
        String.join(",", java.util.Collections.nCopies(billingPointCodes.size(), "?"));
    var arguments = new ArrayList<Object>();
    arguments.add(period);
    arguments.add(cityCode);
    arguments.addAll(billingPointCodes);
    List<String> jsonRows;
    if (datasetType == DatasetType.BILLING_POINT) {
      jsonRows =
          jdbcTemplate.queryForList(
              """
              SELECT s.data_json
                FROM billing_point_snapshot s
               WHERE s.data_period = ? AND s.city_code = ? AND s.billing_point_code IN (
              """
                  + placeholders
                  + ") ORDER BY s.billing_point_code",
              String.class,
              arguments.toArray());
    } else {
      String tableName =
          switch (datasetType) {
            case PAYMENT -> "payment_detail";
            case METER_READING -> "meter_reading";
            case BENCHMARK -> "benchmark_value";
            case BILLING_POINT -> throw new IllegalStateException("Handled above");
          };
      jsonRows =
          jdbcTemplate.queryForList(
              """
              SELECT values_json
                FROM
              """
                  + tableName
                  + """
               WHERE data_period = ? AND city_code = ? AND billing_point_code IN (
              """
                  + placeholders
                  + ") ORDER BY billing_point_code, id",
              String.class,
              arguments.toArray());
    }
    return jsonRows.stream().map(this::readMap).map(value -> (Map<String, String>) value).toList();
  }

  private List<String> billingPointCodes(List<String> publicIds) {
    String placeholders = String.join(",", java.util.Collections.nCopies(publicIds.size(), "?"));
    return jdbcTemplate.queryForList(
        "SELECT billing_point_code FROM billing_point_snapshot WHERE public_id IN ("
            + placeholders
            + ") ORDER BY billing_point_code",
        String.class,
        publicIds.toArray());
  }

  private byte[] zip(Map<String, byte[]> files) {
    try (var output = new ByteArrayOutputStream();
        var zip = new ZipOutputStream(output)) {
      for (var entry : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
      zip.finish();
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("ZIP export could not be generated", exception);
    }
  }

  private String payloadJobId(String json) {
    try {
      return objectMapper.readTree(json).path("exportJobId").asText();
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted export task payload is invalid", exception);
    }
  }

  private LinkedHashMap<String, String> readMap(String json) {
    try {
      return objectMapper.readValue(json, STRING_MAP);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted export row is invalid JSON", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Export result could not be serialized", exception);
    }
  }
}
