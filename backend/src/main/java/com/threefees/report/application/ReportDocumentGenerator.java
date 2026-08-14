package com.threefees.report.application;

import com.threefees.ai.application.AiServiceClient.ReportSections;
import jakarta.annotation.PostConstruct;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.Word6Extractor;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

@Component
public class ReportDocumentGenerator {

  private static final float MARGIN = 55;
  private static final float BODY_FONT_SIZE = 12;
  private static final float LINE_HEIGHT = 20;
  private static final Pattern WORD_FIELD_CODE =
      Pattern.compile(
          "(?is)\\b(?:INCLUDEPICTURE|HYPERLINK)\\b\\s+(?:\"[^\"]*\"|\\\\.|[^\\r\\n])*?(?:MERGEFORMATINET|MERGEFORMAT)?");
  private static final Pattern WORD_FIELD_SWITCH_URL =
      Pattern.compile("(?is)\\\\[a-z]+\\s+\"?https?://[^\\r\\n\"]+\"?\\s*(?:\\\\\\*\\s*)?");
  private static final Pattern RAW_URL = Pattern.compile("(?is)https?://\\S+");
  private static final Pattern WORD_FIELD_REMAINDER =
      Pattern.compile("(?i)\\b(?:MERGEFORMATINET|MERGEFORMAT|INCLUDEPICTURE|HYPERLINK)\\b");

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

  public byte[] generateWordFromHtml(String contentHtml) {
    try (var document = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      String wrapped = "<root>" + normalizeHtml(contentHtml) + "</root>";
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      var dom =
          factory
              .newDocumentBuilder()
              .parse(new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8)));
      appendHtmlChildren(document, dom.getDocumentElement());
      document.write(output);
      return output.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("Word report could not be generated from HTML", exception);
    }
  }

  private String normalizeHtml(String value) {
    String html = value == null ? "" : value.trim();
    html = html.replaceAll("(?i)<br\\s*/?>", "<br />");
    html = html.replaceAll("(?i)<img\\b([^>]*?)(?<!/)>", "<img$1 />");
    html = html.replace("&nbsp;", " ");
    return html;
  }

  private void appendHtmlChildren(XWPFDocument document, Node parent) throws Exception {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      appendHtmlNode(document, child);
    }
  }

  private void appendHtmlNode(XWPFDocument document, Node node) throws Exception {
    if (node.getNodeType() == Node.TEXT_NODE) {
      String text = cleanWordText(node.getTextContent());
      if (!text.isBlank()) {
        appendWordParagraph(document, text, false, 12);
      }
      return;
    }
    if (!(node instanceof Element element)) {
      return;
    }
    String tag = element.getTagName().toLowerCase(java.util.Locale.ROOT);
    switch (tag) {
      case "h1" -> appendWordParagraph(document, element.getTextContent(), true, 18);
      case "h2", "h3" -> appendWordParagraph(document, element.getTextContent(), true, 14);
      case "p", "div", "section", "article" -> appendHtmlChildren(document, element);
      case "br" -> appendWordParagraph(document, " ", false, 12);
      case "table" -> appendHtmlTable(document, element);
      case "img" -> appendHtmlImage(document, element);
      default -> appendHtmlChildren(document, element);
    }
  }

  private void appendWordParagraph(XWPFDocument document, String text, boolean bold, int size) {
    String cleaned = cleanWordText(text);
    if (cleaned.isBlank()) {
      return;
    }
    var paragraph = document.createParagraph();
    var run = paragraph.createRun();
    run.setText(cleaned);
    run.setBold(bold);
    run.setFontFamily(bold ? "SimHei" : "SimSun");
    run.setFontSize(size);
  }

  private void appendHtmlTable(XWPFDocument document, Element tableElement) {
    var rows = tableElement.getElementsByTagName("tr");
    if (rows.getLength() == 0) {
      return;
    }
    var table = document.createTable(rows.getLength(), 1);
    for (int rowIndex = 0; rowIndex < rows.getLength(); rowIndex++) {
      var rowElement = (Element) rows.item(rowIndex);
      var cells = rowElement.getElementsByTagName("td");
      if (cells.getLength() == 0) {
        cells = rowElement.getElementsByTagName("th");
      }
      var row = table.getRow(rowIndex);
      while (row.getTableCells().size() < Math.max(cells.getLength(), 1)) {
        row.addNewTableCell();
      }
      for (int cellIndex = 0; cellIndex < Math.max(cells.getLength(), 1); cellIndex++) {
        row.getCell(cellIndex).setText(cellIndex < cells.getLength() ? cells.item(cellIndex).getTextContent() : "");
      }
    }
  }

  private void appendHtmlImage(XWPFDocument document, Element image) throws Exception {
    String src = image.getAttribute("src");
    if (!src.startsWith("data:image/")) {
      return;
    }
    int comma = src.indexOf(',');
    int semicolon = src.indexOf(';');
    if (comma < 0 || semicolon < 0 || semicolon > comma) {
      return;
    }
    String mediaType = src.substring("data:".length(), semicolon);
    byte[] bytes = Base64.getDecoder().decode(src.substring(comma + 1));
    int type = "image/png".equals(mediaType) ? XWPFDocument.PICTURE_TYPE_PNG : XWPFDocument.PICTURE_TYPE_JPEG;
    var paragraph = document.createParagraph();
    paragraph.setAlignment(ParagraphAlignment.CENTER);
    paragraph
        .createRun()
        .addPicture(new ByteArrayInputStream(bytes), type, "pasted-image", Units.toEMU(420), Units.toEMU(260));
  }

  public String extractWordText(byte[] bytes, String originalName) {
    String lower = originalName.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".docx")) {
      return extractWordTextWithFallbacks(
          List.of(() -> extractDocxText(bytes), () -> extractWithFactory(bytes)));
    }
    if (lower.endsWith(".doc")) {
      return extractWordTextWithFallbacks(
          List.of(
              () -> extractDocText(bytes),
              () -> extractOldDocText(bytes),
              () -> extractWithFactory(bytes)));
    }
    throw new IllegalArgumentException("Only .doc/.docx historical reports are supported");
  }

  public String extractWordPreviewHtml(byte[] bytes, String originalName) {
    String lower = originalName.toLowerCase(java.util.Locale.ROOT);
    try {
      if (lower.endsWith(".docx")) {
        return extractDocxHtml(bytes);
      }
      if (lower.endsWith(".doc")) {
        return extractDocHtml(bytes);
      }
    } catch (IOException | RuntimeException exception) {
      throw new IllegalArgumentException("Word file could not be read", exception);
    }
    throw new IllegalArgumentException("Only .doc/.docx historical reports are supported");
  }

  private String extractDocxHtml(byte[] bytes) throws IOException {
    try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      StringBuilder html = new StringBuilder("<div class=\"word-preview\">");
      appendDocxBodyElements(html, document.getBodyElements());
      html.append("</div>");
      return html.toString();
    }
  }

  private String extractDocHtml(byte[] bytes) throws IOException {
    try (var document = new HWPFDocument(new ByteArrayInputStream(bytes))) {
      StringBuilder html = new StringBuilder("<div class=\"word-preview\">");
      Range range = document.getRange();
      boolean[] tableParagraph = new boolean[Math.max(range.numParagraphs(), 0)];
      TableIterator iterator = new TableIterator(range);
      while (iterator.hasNext()) {
        Table table = iterator.next();
        html.append("<table>");
        for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
          TableRow row = table.getRow(rowIndex);
          html.append("<tr>");
          for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
            TableCell cell = row.getCell(cellIndex);
            html.append("<td>");
            appendDocRange(html, document.getPicturesTable(), cell);
            html.append("</td>");
            markTableParagraphs(range, cell, tableParagraph);
          }
          html.append("</tr>");
        }
        html.append("</table>");
      }
      for (int index = 0; index < range.numParagraphs(); index++) {
        if (!tableParagraph[index]) {
          appendDocParagraph(html, document.getPicturesTable(), range.getParagraph(index));
        }
      }
      html.append("</div>");
      return html.toString();
    }
  }

  private void appendDocxBodyElements(StringBuilder html, List<IBodyElement> elements) {
    for (IBodyElement element : elements) {
      if (element.getElementType() == BodyElementType.PARAGRAPH) {
        appendDocxParagraph(html, (XWPFParagraph) element);
      } else if (element.getElementType() == BodyElementType.TABLE) {
        appendDocxTable(html, (XWPFTable) element);
      }
    }
  }

  private void appendDocxTable(StringBuilder html, XWPFTable table) {
    html.append("<table>");
    for (XWPFTableRow row : table.getRows()) {
      html.append("<tr>");
      for (XWPFTableCell cell : row.getTableCells()) {
        html.append("<td>");
        appendDocxBodyElements(html, cell.getBodyElements());
        html.append("</td>");
      }
      html.append("</tr>");
    }
    html.append("</table>");
  }

  private void appendDocxParagraph(StringBuilder html, XWPFParagraph paragraph) {
    StringBuilder text = new StringBuilder();
    boolean wroteContent = false;
    for (XWPFRun run : paragraph.getRuns()) {
      String runText = cleanWordText(run.text());
      if (!runText.isBlank()) {
        text.append(runText);
      }
      for (XWPFPicture picture : run.getEmbeddedPictures()) {
        wroteContent |= appendBufferedParagraph(html, text);
        XWPFPictureData data = picture.getPictureData();
        if (data != null) {
          appendImage(html, data.getData(), data.getFileName(), data.getPackagePart().getContentType());
          wroteContent = true;
        }
      }
    }
    if (appendBufferedParagraph(html, text)) {
      return;
    }
    if (!wroteContent) {
      appendParagraph(html, paragraph.getText());
    }
  }

  private void appendDocRange(StringBuilder html, PicturesTable picturesTable, Range range) {
    for (int index = 0; index < range.numParagraphs(); index++) {
      appendDocParagraph(html, picturesTable, range.getParagraph(index));
    }
  }

  private void appendDocParagraph(StringBuilder html, PicturesTable picturesTable, Paragraph paragraph) {
    StringBuilder text = new StringBuilder();
    boolean wroteContent = false;
    for (int runIndex = 0; runIndex < paragraph.numCharacterRuns(); runIndex++) {
      CharacterRun run = paragraph.getCharacterRun(runIndex);
      if (picturesTable.hasPicture(run)) {
        wroteContent |= appendBufferedParagraph(html, text);
        Picture picture = picturesTable.extractPicture(run, false);
        if (picture != null) {
          appendImage(html, picture.getContent(), picture.suggestFullFileName(), picture.getMimeType());
          wroteContent = true;
        }
      } else {
        String runText = cleanWordText(run.text());
        if (!runText.isBlank()) {
          text.append(runText);
        }
      }
    }
    if (!appendBufferedParagraph(html, text) && !wroteContent) {
      appendParagraph(html, paragraph.text());
    }
  }

  private void markTableParagraphs(Range range, TableCell cell, boolean[] tableParagraph) {
    int start = cell.getStartOffset();
    int end = cell.getEndOffset();
    for (int index = 0; index < range.numParagraphs(); index++) {
      var paragraph = range.getParagraph(index);
      if (paragraph.getStartOffset() >= start && paragraph.getEndOffset() <= end) {
        tableParagraph[index] = true;
      }
    }
  }

  private void appendImage(StringBuilder html, byte[] bytes, String name, String mediaType) {
    if (bytes == null || bytes.length == 0) {
      return;
    }
    String type = mediaType == null || mediaType.isBlank() ? "image/png" : mediaType;
    html.append("<figure><img alt=\"")
        .append(escapeHtml(name == null ? "Word image" : name))
        .append("\" src=\"data:")
        .append(escapeHtml(type))
        .append(";base64,")
        .append(Base64.getEncoder().encodeToString(bytes))
        .append("\" /></figure>");
  }

  private boolean appendBufferedParagraph(StringBuilder html, StringBuilder text) {
    String cleaned = cleanWordText(text.toString());
    text.setLength(0);
    if (cleaned.isBlank()) {
      return false;
    }
    appendParagraph(html, cleaned);
    return true;
  }

  private void appendParagraph(StringBuilder html, String text) {
    String cleaned = cleanWordText(text);
    if (cleaned.isBlank()) {
      return;
    }
    html.append("<p>");
    appendText(html, cleaned);
    html.append("</p>");
  }

  private void appendText(StringBuilder html, String text) {
    String[] lines = text.split("\\R", -1);
    for (int index = 0; index < lines.length; index++) {
      if (index > 0) {
        html.append("<br />");
      }
      html.append(escapeHtml(lines[index]));
    }
  }

  private String escapeHtml(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      switch (codePoint) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&#39;");
        default -> escaped.appendCodePoint(codePoint);
      }
      offset += Character.charCount(codePoint);
    }
    return escaped.toString();
  }

  private String extractDocxText(byte[] bytes) throws IOException {
    try (var document = new XWPFDocument(new ByteArrayInputStream(bytes));
        var extractor = new XWPFWordExtractor(document)) {
      return cleanWordText(extractor.getText());
    }
  }

  private String extractDocText(byte[] bytes) throws IOException {
    try (var document = new HWPFDocument(new ByteArrayInputStream(bytes))) {
      String rangeText = cleanWordText(document.getRange().text());
      if (!rangeText.isBlank()) {
        return rangeText;
      }
      try (var extractor = new WordExtractor(document)) {
        return cleanWordText(extractor.getText());
      }
    }
  }

  private String extractOldDocText(byte[] bytes) throws IOException {
    try (var extractor = new Word6Extractor(new ByteArrayInputStream(bytes))) {
      return cleanWordText(extractor.getText());
    }
  }

  private String extractWithFactory(byte[] bytes) throws Exception {
    try (POITextExtractor extractor =
        ExtractorFactory.createExtractor(new ByteArrayInputStream(bytes))) {
      return cleanWordText(extractor.getText());
    }
  }

  private String cleanWordText(String value) {
    if (value == null) {
      return "";
    }
    String normalized =
        value
            .replace('\u0013', ' ')
            .replace('\u0014', ' ')
            .replace('\u0015', ' ')
            .replace('\u0007', '\n')
            .replace('\u000b', '\n')
            .replace('\r', '\n');
    normalized = WORD_FIELD_CODE.matcher(normalized).replaceAll(" ");
    normalized = WORD_FIELD_SWITCH_URL.matcher(normalized).replaceAll(" ");
    normalized = RAW_URL.matcher(normalized).replaceAll(" ");
    normalized = WORD_FIELD_REMAINDER.matcher(normalized).replaceAll(" ");
    return stripUnsupportedControlCharacters(normalized).trim();
  }

  private String extractWordTextWithFallbacks(List<WordTextExtractor> extractors) {
    List<Throwable> failures = new ArrayList<>();
    boolean extractedAnyText = false;
    for (WordTextExtractor extractor : extractors) {
      try {
        String text = extractor.extract();
        extractedAnyText = true;
        if (!text.isBlank()) {
          return text;
        }
      } catch (Exception | LinkageError exception) {
        failures.add(exception);
      }
    }
    if (extractedAnyText) {
      return "";
    }
    IllegalArgumentException exception = new IllegalArgumentException("Word file could not be read");
    failures.forEach(exception::addSuppressed);
    throw exception;
  }

  private static String stripUnsupportedControlCharacters(String value) {
    StringBuilder cleaned = new StringBuilder(value.length());
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      if (codePoint == '\n' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
        cleaned.appendCodePoint(codePoint);
      }
      offset += Character.charCount(codePoint);
    }
    return cleaned.toString();
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

  @FunctionalInterface
  private interface WordTextExtractor {
    String extract() throws Exception;
  }

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
      for (String sourceLine : stripUnsupportedControlCharacters(text).split("\\R", -1)) {
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
