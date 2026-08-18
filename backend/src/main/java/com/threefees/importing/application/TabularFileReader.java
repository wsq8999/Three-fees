package com.threefees.importing.application;

import com.threefees.identity.application.BusinessRuleException;
import com.threefees.importing.domain.DatasetType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TabularFileReader {

  /**
   * 最多在每个 Sheet 的前 50 个非空行中寻找真正表头。
   *
   * <p>这样允许 Excel 前面存在：
   *
   * <p>标题、说明、导出时间、填写说明等内容。
   */
  private static final int MAX_HEADER_SEARCH_ROWS = 50;

  private static final String BILLING_POINT_CODE = "报账点编码";
  private static final String BILLING_POINT_NAME = "报账点名称";
  private static final String CITY = "所属地市";
  private static final String BENCHMARK_CITY = "地市";

  private static final String PAYMENT_CODE = "缴费单编码";
  private static final String PERIOD_START = "缴费期始";
  private static final String PERIOD_END = "缴费期终";
  private static final String LAST_PERIOD_START = "最后报账期始";
  private static final String AUDIT_STATUS = "审核状态";
  private static final String ACTUAL_AMOUNT = "实际报账金额";

  private static final String METER_CODE = "电表编码";
  private static final String ALLOCATED_ENERGY = "分摊后度数";

  private static final String YEAR = "年份";
  private static final String MONTH = "月份";
  private static final String MONTHLY_BENCHMARK = "月总标杆";
  private static final String MONTHLY_AVERAGE_BENCHMARK = "月平均标杆";

  private final int maxRows;

  public TabularFileReader(@Value("${app.file.max-import-rows:200000}") int maxRows) {
    this.maxRows = maxRows;
  }

  /**
   * 唯一导入读取入口。
   *
   * <p>注意：这里故意不再保留旧的 read(bytes, fileName) 方法。
   *
   * <p>所有导入都必须传 datasetType，这样系统才能准确判断应该寻找：
   *
   * <p>报账点清单、缴费明细、电表读数或标杆值。
   */
  public TabularData read(byte[] bytes, String originalName, DatasetType datasetType) {

    if (datasetType == null) {
      throw new BusinessRuleException("IMPORT_DATASET_TYPE_MISSING", "导入类型不能为空");
    }

    String extension = extension(originalName);

    return switch (extension) {
      case "xlsx", "xls" -> readWorkbook(bytes, datasetType);

      case "csv" -> readCsv(bytes, datasetType);

      default ->
          throw new BusinessRuleException("IMPORT_FILE_TYPE_INVALID", "导入文件格式不支持，仅支持 xlsx、xls、csv");
    };
  }

  /**
   * Excel读取。
   *
   * <p>核心变化：
   *
   * <p>1. 不再固定读取第一个Sheet。
   *
   * <p>2. 遍历全部Sheet。
   *
   * <p>3. 每个Sheet前50个非空行自动寻找真正表头。
   *
   * <p>4. 根据当前导入类型判断哪个Sheet最符合要求。
   */
  private TabularData readWorkbook(byte[] bytes, DatasetType datasetType) {

    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {

      if (workbook.getNumberOfSheets() == 0) {
        throw new BusinessRuleException("IMPORT_SHEET_MISSING", "Excel 不包含工作表");
      }

      DataFormatter formatter = new DataFormatter(Locale.ROOT, true);

      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

      SheetCandidate bestCandidate = null;

      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {

        Sheet sheet = workbook.getSheetAt(sheetIndex);

        List<List<String>> rows = readSheetRows(sheet, formatter, evaluator);

        /*
         * 至少需要：
         * 一行表头 + 一行实际数据。
         */
        if (rows.size() < 2) {
          continue;
        }

        HeaderCandidate headerCandidate = findBestHeader(rows, datasetType);

        if (headerCandidate == null) {
          continue;
        }

        List<List<String>> selectedRows =
            new ArrayList<>(rows.subList(headerCandidate.rowIndex(), rows.size()));

        if (selectedRows.size() < 2) {
          continue;
        }

        SheetCandidate candidate =
            new SheetCandidate(sheet.getSheetName(), selectedRows, headerCandidate.score());

        /*
         * 优先级：
         *
         * 1. 表头命中的关键字段数量更多；
         * 2. 命中数量相同时，数据行更多。
         */
        if (bestCandidate == null
            || candidate.score() > bestCandidate.score()
            || (candidate.score() == bestCandidate.score()
                && candidate.rows().size() > bestCandidate.rows().size())) {

          bestCandidate = candidate;
        }
      }

      if (bestCandidate == null) {
        throw new BusinessRuleException("IMPORT_DATA_EMPTY", "Excel 中未找到可导入的数据工作表");
      }

      int minimumScore = minimumHeaderScore(datasetType);

      if (bestCandidate.score() < minimumScore) {

        throw new BusinessRuleException("IMPORT_SHEET_NOT_RECOGNIZED", "未找到与当前导入类型匹配的数据工作表，请检查表头");
      }

      validateRowLimit(bestCandidate.rows());

      return toTabularData(bestCandidate.rows());

    } catch (IOException | RuntimeException exception) {

      if (exception instanceof BusinessRuleException businessRuleException) {

        throw businessRuleException;
      }

      throw new BusinessRuleException("IMPORT_EXCEL_INVALID", "无法解析 Excel 文件");
    }
  }

  /**
   * 把一个Sheet中的所有非空行读取出来。
   *
   * <p>不会删除行内部的空单元格，因此列的位置仍然保持正确。
   */
  private List<List<String>> readSheetRows(
      Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {

    List<List<String>> rows = new ArrayList<>();

    int firstRow = sheet.getFirstRowNum();

    int lastRow = sheet.getLastRowNum();

    for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {

      var row = sheet.getRow(rowIndex);

      if (row == null) {
        continue;
      }

      int width = row.getLastCellNum();

      if (width < 0) {
        continue;
      }

      List<String> values = new ArrayList<>(width);

      boolean nonBlank = false;

      for (int column = 0; column < width; column++) {

        String value = formatter.formatCellValue(row.getCell(column), evaluator).trim();

        values.add(value);

        if (!value.isBlank()) {
          nonBlank = true;
        }
      }

      /*
       * 整行完全为空直接跳过。
       */
      if (nonBlank) {
        rows.add(values);
      }

      /*
       * 防止恶意或异常超大文件。
       *
       * 多预留前面的说明/标题行。
       */
      if (rows.size() > maxRows + MAX_HEADER_SEARCH_ROWS + 1) {

        throw new BusinessRuleException("IMPORT_ROW_LIMIT_EXCEEDED", "导入数据超过最大行数");
      }
    }

    return rows;
  }

  /** 在当前Sheet前50个非空行中寻找最可能的真正表头。 */
  private HeaderCandidate findBestHeader(List<List<String>> rows, DatasetType datasetType) {

    int limit = Math.min(rows.size(), MAX_HEADER_SEARCH_ROWS);

    HeaderCandidate best = null;

    for (int rowIndex = 0; rowIndex < limit; rowIndex++) {

      int score = scoreHeader(rows.get(rowIndex), datasetType);

      HeaderCandidate candidate = new HeaderCandidate(rowIndex, score);

      if (best == null || candidate.score() > best.score()) {

        best = candidate;
      }
    }

    return best;
  }

  /** 根据当前导入类型的核心字段判断某一行像不像真正的表头。 */
  private int scoreHeader(List<String> row, DatasetType datasetType) {

    Set<String> actualHeaders = new HashSet<>();

    for (String header : row) {

      String normalized = normalizeHeader(header);

      if (!normalized.isBlank()) {
        actualHeaders.add(normalized);
      }
    }

    int score = 0;

    for (String expected : importantHeaders(datasetType)) {

      if (actualHeaders.contains(normalizeHeader(expected))) {

        score++;
      }
    }

    return score;
  }

  /**
   * 用于识别不同文件类型的核心字段。
   *
   * <p>这里只用于“寻找正确Sheet和表头”，不是最终业务必填校验。
   */
  private List<String> importantHeaders(DatasetType datasetType) {

    return switch (datasetType) {
      case BILLING_POINT ->
          List.of(BILLING_POINT_CODE, BILLING_POINT_NAME, CITY, LAST_PERIOD_START);

      case PAYMENT ->
          List.of(
              PAYMENT_CODE,
              BILLING_POINT_CODE,
              BILLING_POINT_NAME,
              CITY,
              PERIOD_START,
              PERIOD_END,
              AUDIT_STATUS,
              ACTUAL_AMOUNT);

      case METER_READING ->
          List.of(
              PAYMENT_CODE,
              BILLING_POINT_CODE,
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

  /**
   * 自动识别Sheet时至少要命中的关键字段数量。
   *
   * <p>这样不会因为“说明Sheet”偶然出现一个报账点编码就误认为是真实数据。
   */
  private int minimumHeaderScore(DatasetType datasetType) {

    return switch (datasetType) {
      case BILLING_POINT -> 3;

      case PAYMENT -> 5;

      case METER_READING -> 4;

      case BENCHMARK -> 4;
    };
  }

  /**
   * CSV读取。
   *
   * <p>CSV虽然没有Sheet，但也允许前面存在少量说明行， 系统同样会自动寻找真正表头。
   */
  private TabularData readCsv(byte[] bytes, DatasetType datasetType) {

    Charset charset = detectCsvCharset(bytes);

    try (Reader reader =
            new InputStreamReader(new ByteArrayInputStream(stripUtf8Bom(bytes)), charset);
        CSVParser parser =
            CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).get().parse(reader)) {

      List<List<String>> rawRows = new ArrayList<>();

      parser.forEach(
          record -> {
            List<String> values = new ArrayList<>(record.size());

            boolean nonBlank = false;

            for (String value : record) {

              String normalized = value == null ? "" : value.trim();

              values.add(normalized);

              if (!normalized.isBlank()) {
                nonBlank = true;
              }
            }

            if (nonBlank) {
              rawRows.add(values);
            }

            if (rawRows.size() > maxRows + MAX_HEADER_SEARCH_ROWS + 1) {

              throw new BusinessRuleException("IMPORT_ROW_LIMIT_EXCEEDED", "导入数据超过最大行数");
            }
          });

      if (rawRows.size() < 2) {
        throw new BusinessRuleException("IMPORT_DATA_EMPTY", "文件必须包含表头和至少一行数据");
      }

      HeaderCandidate headerCandidate = findBestHeader(rawRows, datasetType);

      if (headerCandidate == null || headerCandidate.score() < minimumHeaderScore(datasetType)) {

        throw new BusinessRuleException("IMPORT_HEADER_NOT_RECOGNIZED", "未找到与当前导入类型匹配的 CSV 表头");
      }

      List<List<String>> selectedRows =
          new ArrayList<>(rawRows.subList(headerCandidate.rowIndex(), rawRows.size()));

      validateRowLimit(selectedRows);

      return toTabularData(selectedRows);

    } catch (IOException exception) {

      throw new BusinessRuleException("IMPORT_CSV_INVALID", "无法解析 CSV 文件");
    }
  }

  /**
   * 数据行最大数量检查。
   *
   * <p>rows 第一行是表头，因此最大为 maxRows + 1。
   */
  private void validateRowLimit(List<List<String>> rows) {

    if (rows.size() > maxRows + 1) {

      throw new BusinessRuleException("IMPORT_ROW_LIMIT_EXCEEDED", "导入数据超过最大行数");
    }
  }

  /** 转换为系统统一表格数据结构。 */
  private TabularData toTabularData(List<List<String>> rows) {

    if (rows.size() < 2) {

      throw new BusinessRuleException("IMPORT_DATA_EMPTY", "文件必须包含表头和至少一行数据");
    }

    return new TabularData(rows.getFirst(), rows.subList(1, rows.size()));
  }

  /**
   * 表头标准化。
   *
   * <p>支持：
   *
   * <p>1. 前后空格；
   *
   * <p>2. 全角空格；
   *
   * <p>3. 表头内部普通空格；
   *
   * <p>4. 月平均标杆 = 月总标杆；
   *
   * <p>5. 计费方式.1 / 计费方式__2 = 计费方式。
   */
  private String normalizeHeader(String header) {

    if (header == null) {
      return "";
    }

    String normalized = header.replace('\u3000', ' ').trim().replaceAll("\\s+", "");

    if (MONTHLY_AVERAGE_BENCHMARK.equals(normalized)) {

      return MONTHLY_BENCHMARK;
    }

    if ("计费方式.1".equals(normalized) || "计费方式__2".equals(normalized)) {

      return "计费方式";
    }

    return normalized;
  }

  /**
   * CSV编码：
   *
   * <p>优先UTF-8，无法解码时使用GB18030。
   */
  private Charset detectCsvCharset(byte[] bytes) {

    byte[] withoutBom = stripUtf8Bom(bytes);

    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);

    try {

      decoder.decode(ByteBuffer.wrap(withoutBom));

      return StandardCharsets.UTF_8;

    } catch (java.nio.charset.CharacterCodingException exception) {

      return Charset.forName("GB18030");
    }
  }

  private byte[] stripUtf8Bom(byte[] bytes) {

    if (bytes.length >= 3
        && bytes[0] == (byte) 0xef
        && bytes[1] == (byte) 0xbb
        && bytes[2] == (byte) 0xbf) {

      return java.util.Arrays.copyOfRange(bytes, 3, bytes.length);
    }

    return bytes;
  }

  private String extension(String originalName) {

    if (originalName == null || originalName.isBlank()) {

      return "";
    }

    int separator = originalName.lastIndexOf('.');

    if (separator < 0 || separator == originalName.length() - 1) {

      return "";
    }

    return originalName.substring(separator + 1).toLowerCase(Locale.ROOT);
  }

  private record HeaderCandidate(int rowIndex, int score) {}

  private record SheetCandidate(String sheetName, List<List<String>> rows, int score) {}
}
