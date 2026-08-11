package com.threefees.report.application;

import com.threefees.ai.application.AiServiceClient.ReportSections;
import jakarta.annotation.PostConstruct;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReportDocumentGenerator {

  private static final float MARGIN = 55;
  private static final float BODY_FONT_SIZE = 12;
  private static final float LINE_HEIGHT = 20;

  private final String configuredFontPath;

  public ReportDocumentGenerator(@Value("${app.report.font-path:}") String configuredFontPath) {
    this.configuredFontPath = configuredFontPath;
  }

  @PostConstruct
  void validateFontConfiguration() {
    Path fontPath = resolveFontPath();
    try (var document = new PDDocument();
        var input = Files.newInputStream(fontPath)) {
      PDFont font = PDType0Font.load(document, input, false);
      font.getStringWidth("江苏物业电费稽核报告");
    } catch (IOException | IllegalArgumentException exception) {
      throw new IllegalStateException(
          "REPORT_FONT_PATH could not be loaded as a Chinese TrueType/OpenType font: " + fontPath,
          exception);
    }
  }

  public GeneratedDocuments generate(ReportSections sections, List<ReportImage> images) {
    return new GeneratedDocuments(generateWord(sections, images), generatePdf(sections, images));
  }

  public String extractWordText(byte[] bytes, String originalName) {
    String lower = originalName.toLowerCase(java.util.Locale.ROOT);
    try {
      if (lower.endsWith(".docx")) {
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes));
            var extractor = new XWPFWordExtractor(document)) {
          return extractor.getText().trim();
        }
      }
      if (lower.endsWith(".doc")) {
        try (var document = new HWPFDocument(new ByteArrayInputStream(bytes));
            var extractor = new WordExtractor(document)) {
          return extractor.getText().trim();
        }
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("Word 文件无法读取", exception);
    }
    throw new IllegalArgumentException("仅支持 .doc/.docx 历史报告");
  }

  public byte[] generateHistoricalPdf(String title, String extractedText) {
    ReportSections sections =
        new ReportSections(title, extractedText, "历史报告原文转换预览", "以原 Word 最终内容为准");
    return generatePdf(sections, List.of());
  }

  private byte[] generateWord(ReportSections sections, List<ReportImage> images) {
    try (var document = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      var title = document.createParagraph();
      title.setAlignment(ParagraphAlignment.CENTER);
      var titleRun = title.createRun();
      titleRun.setText(sections.title());
      titleRun.setBold(true);
      titleRun.setFontFamily("SimHei");
      titleRun.setFontSize(18);
      addSection(document, "一、情况说明", sections.situation());
      addSection(document, "二、排查分析", sections.analysis());
      addSection(document, "三、整改小结", sections.rectification());
      for (ReportImage image : images) {
        var paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        int type =
            image.mediaType().equals("image/png")
                ? XWPFDocument.PICTURE_TYPE_PNG
                : XWPFDocument.PICTURE_TYPE_JPEG;
        paragraph
            .createRun()
            .addPicture(
                new ByteArrayInputStream(image.bytes()),
                type,
                image.name(),
                Units.toEMU(420),
                Units.toEMU(260));
      }
      document.write(output);
      return output.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("Word report could not be generated", exception);
    }
  }

  private void addSection(XWPFDocument document, String heading, String content) {
    var headingParagraph = document.createParagraph();
    var headingRun = headingParagraph.createRun();
    headingRun.setText(heading);
    headingRun.setBold(true);
    headingRun.setFontFamily("SimHei");
    headingRun.setFontSize(14);
    for (String line : content.split("\\R", -1)) {
      var paragraph = document.createParagraph();
      var run = paragraph.createRun();
      run.setText(line.isBlank() ? " " : line);
      run.setFontFamily("SimSun");
      run.setFontSize(12);
    }
  }

  private byte[] generatePdf(ReportSections sections, List<ReportImage> images) {
    Path fontPath = resolveFontPath();
    try (var document = new PDDocument();
        var fontInput = Files.newInputStream(fontPath);
        var output = new ByteArrayOutputStream()) {
      PDFont font = PDType0Font.load(document, fontInput, false);
      PdfWriter writer = new PdfWriter(document, font);
      writer.centered(sections.title(), 18);
      writer.heading("一、情况说明");
      writer.paragraph(sections.situation());
      writer.heading("二、排查分析");
      writer.paragraph(sections.analysis());
      writer.heading("三、整改小结");
      writer.paragraph(sections.rectification());
      for (ReportImage image : images) {
        writer.image(image);
      }
      writer.close();
      document.save(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("PDF report could not be generated", exception);
    }
  }

  private Path resolveFontPath() {
    if (configuredFontPath != null && !configuredFontPath.isBlank()) {
      Path configured = Path.of(configuredFontPath).toAbsolutePath().normalize();
      String fileName = configured.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
      if (fileName.endsWith(".ttc")) {
        throw new IllegalStateException(
            "REPORT_FONT_PATH does not support TrueType collections (.ttc); configure a .ttf or .otf file");
      }
      if (!(fileName.endsWith(".ttf") || fileName.endsWith(".otf"))) {
        throw new IllegalStateException("REPORT_FONT_PATH must reference a .ttf or .otf font file");
      }
      if (!Files.isRegularFile(configured) || !Files.isReadable(configured)) {
        throw new IllegalStateException("REPORT_FONT_PATH must reference a readable font file");
      }
      return configured;
    }
    return List.of(
            Path.of("C:/Windows/Fonts/NotoSansSC-VF.ttf"), Path.of("C:/Windows/Fonts/simhei.ttf"))
        .stream()
        .filter(Files::isRegularFile)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "REPORT_FONT_PATH must reference a readable Chinese TrueType font"));
  }

  public record GeneratedDocuments(byte[] word, byte[] pdf) {}

  public record ReportImage(String name, String mediaType, byte[] bytes) {
    public ReportImage {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private static final class PdfWriter {

    private final PDDocument document;
    private final PDFont font;
    private PDPage page;
    private PDPageContentStream stream;
    private float y;

    private PdfWriter(PDDocument document, PDFont font) throws IOException {
      this.document = document;
      this.font = font;
      newPage();
    }

    private void centered(String text, float size) throws IOException {
      ensureSpace(40);
      float width = font.getStringWidth(text) / 1000 * size;
      writeLine(text, size, Math.max(MARGIN, (PDRectangle.A4.getWidth() - width) / 2));
      y -= 14;
    }

    private void heading(String text) throws IOException {
      ensureSpace(35);
      writeLine(text, 14, MARGIN);
      y -= 6;
    }

    private void paragraph(String text) throws IOException {
      float available = PDRectangle.A4.getWidth() - MARGIN * 2;
      for (String sourceLine : text.split("\\R", -1)) {
        for (String line : wrap(sourceLine.isBlank() ? " " : sourceLine, available)) {
          ensureSpace(LINE_HEIGHT);
          writeLine(line, BODY_FONT_SIZE, MARGIN);
        }
      }
      y -= 8;
    }

    private void image(ReportImage reportImage) throws IOException {
      BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(reportImage.bytes()));
      if (buffered == null) {
        return;
      }
      float maxWidth = PDRectangle.A4.getWidth() - MARGIN * 2;
      float width = Math.min(maxWidth, buffered.getWidth());
      float height = width * buffered.getHeight() / buffered.getWidth();
      if (height > 300) {
        width = width * 300 / height;
        height = 300;
      }
      ensureSpace(height + 20);
      PDImageXObject image =
          PDImageXObject.createFromByteArray(document, reportImage.bytes(), reportImage.name());
      stream.drawImage(image, MARGIN, y - height, width, height);
      y -= height + 15;
    }

    private List<String> wrap(String text, float availableWidth) throws IOException {
      var lines = new ArrayList<String>();
      var current = new StringBuilder();
      for (int offset = 0; offset < text.length(); ) {
        int codePoint = text.codePointAt(offset);
        String character = new String(Character.toChars(codePoint));
        String candidate = current + character;
        float width = font.getStringWidth(candidate) / 1000 * BODY_FONT_SIZE;
        if (width > availableWidth && !current.isEmpty()) {
          lines.add(current.toString());
          current.setLength(0);
        }
        current.append(character);
        offset += Character.charCount(codePoint);
      }
      lines.add(current.toString());
      return lines;
    }

    private void writeLine(String text, float size, float x) throws IOException {
      stream.beginText();
      stream.setFont(font, size);
      stream.newLineAtOffset(x, y);
      stream.showText(text);
      stream.endText();
      y -= LINE_HEIGHT;
    }

    private void ensureSpace(float required) throws IOException {
      if (y - required < MARGIN) {
        stream.close();
        newPage();
      }
    }

    private void newPage() throws IOException {
      page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      stream = new PDPageContentStream(document, page);
      y = PDRectangle.A4.getHeight() - MARGIN;
    }

    private void close() throws IOException {
      stream.close();
    }
  }
}
