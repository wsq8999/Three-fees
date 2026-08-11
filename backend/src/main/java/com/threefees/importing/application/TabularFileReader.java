package com.threefees.importing.application;

import com.threefees.identity.application.BusinessRuleException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TabularFileReader {

  private final int maxRows;

  public TabularFileReader(@Value("${app.file.max-import-rows:200000}") int maxRows) {
    this.maxRows = maxRows;
  }

  public TabularData read(byte[] bytes, String originalName) {
    String extension = extension(originalName);
    return switch (extension) {
      case "xlsx", "xls" -> readWorkbook(bytes);
      case "csv" -> readCsv(bytes);
      default -> throw new BusinessRuleException("IMPORT_FILE_TYPE_INVALID", "导入文件格式不支持");
    };
  }

  private TabularData readWorkbook(byte[] bytes) {
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (workbook.getNumberOfSheets() == 0) {
        throw new BusinessRuleException("IMPORT_SHEET_MISSING", "Excel 不包含工作表");
      }
      var sheet = workbook.getSheetAt(0);
      var formatter = new DataFormatter(Locale.ROOT, true);
      var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      var rawRows = new ArrayList<List<String>>();
      int lastRow = sheet.getLastRowNum();
      for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= lastRow; rowIndex++) {
        var row = sheet.getRow(rowIndex);
        if (row == null) {
          continue;
        }
        int width = row.getLastCellNum();
        if (width < 0) {
          continue;
        }
        var values = new ArrayList<String>(width);
        boolean nonBlank = false;
        for (int column = 0; column < width; column++) {
          String value = formatter.formatCellValue(row.getCell(column), evaluator).trim();
          values.add(value);
          nonBlank |= !value.isBlank();
        }
        if (nonBlank) {
          rawRows.add(values);
        }
        if (rawRows.size() > maxRows + 1) {
          throw new BusinessRuleException("IMPORT_ROW_LIMIT_EXCEEDED", "导入数据超过最大行数");
        }
      }
      return toTabularData(rawRows);
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof BusinessRuleException businessRuleException) {
        throw businessRuleException;
      }
      throw new BusinessRuleException("IMPORT_EXCEL_INVALID", "无法解析 Excel 文件");
    }
  }

  private TabularData readCsv(byte[] bytes) {
    Charset charset = detectCsvCharset(bytes);
    try (Reader reader =
            new InputStreamReader(new ByteArrayInputStream(stripUtf8Bom(bytes)), charset);
        CSVParser parser =
            CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).get().parse(reader)) {
      var rawRows = new ArrayList<List<String>>();
      parser.forEach(
          record -> {
            if (rawRows.size() > maxRows) {
              throw new BusinessRuleException("IMPORT_ROW_LIMIT_EXCEEDED", "导入数据超过最大行数");
            }
            var values = new ArrayList<String>(record.size());
            record.forEach(value -> values.add(value.trim()));
            rawRows.add(values);
          });
      return toTabularData(rawRows);
    } catch (IOException exception) {
      throw new BusinessRuleException("IMPORT_CSV_INVALID", "无法解析 CSV 文件");
    }
  }

  private TabularData toTabularData(List<List<String>> rows) {
    if (rows.size() < 2) {
      throw new BusinessRuleException("IMPORT_DATA_EMPTY", "文件必须包含表头和至少一行数据");
    }
    return new TabularData(rows.getFirst(), rows.subList(1, rows.size()));
  }

  private Charset detectCsvCharset(byte[] bytes) {
    byte[] withoutBom = stripUtf8Bom(bytes);
    var utf8 =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      utf8.decode(ByteBuffer.wrap(withoutBom));
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
    int separator = originalName.lastIndexOf('.');
    return separator < 0 ? "" : originalName.substring(separator + 1).toLowerCase(Locale.ROOT);
  }
}
