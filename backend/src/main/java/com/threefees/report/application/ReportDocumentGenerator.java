package com.threefees.report.application;

import com.threefees.ai.application.AiServiceClient.ReportSections;
import jakarta.annotation.PostConstruct;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.Word6Extractor;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
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
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;
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
  private static final Pattern HTML_TAG = Pattern.compile("(?is)<[a-z][^>]*>");
  private static final Pattern INLINE_FILE_ID =
      Pattern.compile("(?is)data-file-id=[\"']([^\"']+)[\"']");
  private static final Pattern CAUSE_LABEL_BOUNDARY =
      Pattern.compile(
          "(?<!^)(?=本期(?:电量)?(?:同比|环比|额定(?:标杆)?)超标原因[：:])");
  private static final Pattern CAUSE_LABEL =
      Pattern.compile("(?:本期(?:电量)?(?:同比|环比|额定(?:标杆)?)超标原因|超标原因(?:是|为)?)[：:，,]?");
  private static final Pattern WORD_INLINE_IMAGE_SPAN =
      Pattern.compile(
          "(?is)<span\\b(?=[^>]*\\bclass=[\"']word-inline-image[\"'])[^>]*>\\s*<img\\b[^>]*>\\s*</span>");
  private static final Pattern HTML_LINE_BREAK = Pattern.compile("(?is)<br\\s*/?>");
  private static final double WORD_PAGE_IMAGE_MAX_WIDTH_POINTS = 420;
  private static final int BODY_FIRST_LINE_INDENT_TWIPS = 480;
  private static final Set<String> CAUSE_LABEL_PHRASES =
      Set.of(
          "本期电量同比超标原因：",
          "本期电量环比超标原因：",
          "本期额定标杆超标原因：",
          "本期电量同比超标原因:",
          "本期电量环比超标原因:",
          "本期额定标杆超标原因:");
  private static final List<String> IMPORTANT_REASON_PHRASES =
      List.of(
          "资管系统未及时更新",
          "额定功率台账未及时更新",
          "实际用电情况正常",
          "极简站改造新增机柜及空调长时间运行所致",
          "不存在用电量跑冒滴漏现象，不存在偷搭电问题",
          "不存在用电量跑冒滴漏",
          "不存在跑冒滴漏",
          "不存在偷搭电",
          "分摊比例变化",
          "电信下电退出分摊",
          "电信设备已下电退出电费分摊",
          "设备新增",
          "站址搬迁",
          "合并电表",
          "空调长时间运行");
  private static final Pattern METRIC_COMPARISON_EMPHASIS =
      Pattern.compile(".*(?:本期日均|正常上限|超标\\d+(?:\\.\\d+)?%|超标比例).*");

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
    return generateWordFromHtml(contentHtml, List.of());
  }

  public byte[] generateWordFromHtml(String contentHtml, List<ReportImage> images) {
    try (var document = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      Map<String, ReportImage> imagesById =
          images.stream()
              .collect(Collectors.toMap(ReportImage::fileId, Function.identity(), (a, b) -> a));
      appendHtmlFragment(document, contentHtml, new HtmlRenderContext(imagesById, new java.util.HashSet<>()));
      document.write(output);
      return output.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("Word report could not be generated from HTML", exception);
    }
  }

  private String normalizeHtml(String value) {
    String html = value == null ? "" : value.trim();
    html = html.replace("&nbsp;", " ");
    html = html.replaceAll("(?i)\\scontenteditable=(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
    html = html.replaceAll("(?i)\\sdraggable=(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
    html = html.replaceAll("(?i)\\sdata-inline-image-spacer=(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
    html = normalizeVoidHtmlTag(html, "br");
    html = normalizeVoidHtmlTag(html, "img");
    html = normalizeVoidHtmlTag(html, "hr");
    html = normalizeVoidHtmlTag(html, "input");
    return html;
  }

  private String normalizeVoidHtmlTag(String html, String tag) {
    return html.replaceAll("(?i)<" + tag + "\\b([^>]*)>", "<" + tag + "$1 />")
        .replaceAll("(?i)<" + tag + "\\b([^>]*)/\\s*/>", "<" + tag + "$1 />");
  }

  private void appendHtmlFragment(
      XWPFDocument document, String contentHtml, HtmlRenderContext context)
      throws Exception {
    String wrapped = "<root>" + normalizeHtml(contentHtml) + "</root>";
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setExpandEntityReferences(false);
    var dom =
        factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8)));
    appendHtmlChildren(document, dom.getDocumentElement(), context);
  }

  private void appendHtmlChildren(
      XWPFDocument document, Node parent, HtmlRenderContext context) throws Exception {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      appendHtmlNode(document, child, context);
    }
  }

  private void appendHtmlNode(XWPFDocument document, Node node, HtmlRenderContext context)
      throws Exception {
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
    if (hasEditorOnlyClass(element)) {
      return;
    }
    if (hasClass(element, "inline-image-row")) {
      appendHtmlImageRow(document, element, context);
      return;
    }
    String tag = element.getTagName().toLowerCase(java.util.Locale.ROOT);
    switch (tag) {
      case "h1" -> appendWordParagraph(document, element.getTextContent(), true, 18, true);
      case "h2", "h3" -> appendWordParagraph(document, element.getTextContent(), true, 14, false);
      case "p", "div" -> appendHtmlBlock(document, element, context);
      case "section", "article" -> appendHtmlChildren(document, element, context);
      case "figure" -> appendHtmlInlineBlock(document, element, context);
      case "br" -> appendBlankWordParagraph(document);
      case "table" -> appendHtmlTable(document, element, context);
      case "img" -> appendHtmlImage(document, element, context);
      default -> appendHtmlChildren(document, element, context);
    }
  }

  private void appendHtmlBlock(
      XWPFDocument document, Element element, HtmlRenderContext context)
      throws Exception {
    if (containsElement(element, Set.of("table", "h1", "h2", "h3"))) {
      appendHtmlChildren(document, element, context);
      return;
    }
    if (containsInlineImageRow(element)) {
      appendHtmlChildren(document, element, context);
      return;
    }
    if (containsElement(element, Set.of("img", "figure"))) {
      appendHtmlInlineBlock(document, element, context);
      return;
    }
    String text = cleanWordText(element.getTextContent());
    if (text.isBlank()) {
      return;
    }
    var paragraph = document.createParagraph();
    applyParagraphStyle(paragraph, false, false);
    appendInlineRuns(paragraph, element, false, 12);
  }

  private void appendHtmlInlineBlock(
      XWPFDocument document, Element element, HtmlRenderContext context)
      throws Exception {
    if (cleanWordText(element.getTextContent()).isBlank()
        && !containsElement(element, Set.of("img"))) {
      return;
    }
    var paragraph = document.createParagraph();
    applyParagraphStyle(paragraph, false, false);
    appendInlineRunsWithImages(paragraph, element, context, false, 12);
  }

  private void appendHtmlImageRow(
      XWPFDocument document, Element element, HtmlRenderContext context)
      throws Exception {
    var images = descendantElements(element, "img");
    if (images.isEmpty()) {
      return;
    }
    var rowImages = new ArrayList<RowImage>();
    for (Element image : images) {
      if (isDuplicateImage(image, context)) {
        continue;
      }
      ImageBytes bytes = htmlImageBytes(image, context.imagesById());
      if (bytes == null) {
        continue;
      }
      rowImages.add(new RowImage(image, wordImageSize(bytes.bytes(), htmlImageDisplaySize(image))));
    }
    if (rowImages.isEmpty()) {
      return;
    }
    for (List<RowImage> rowImagesOnLine : wrapRowImages(rowImages)) {
      appendInlineImageTableRow(document, rowImagesOnLine, context);
    }
  }

  private List<List<RowImage>> wrapRowImages(List<RowImage> images) {
    var rows = new ArrayList<List<RowImage>>();
    var current = new ArrayList<RowImage>();
    double currentWidth = 0;
    for (RowImage image : images) {
      double imageWidth = emuToPoints(image.size().widthEmu());
      double nextWidth = currentWidth + (current.isEmpty() ? 0 : 6.0) + imageWidth;
      if (!current.isEmpty() && nextWidth > WORD_PAGE_IMAGE_MAX_WIDTH_POINTS) {
        rows.add(current);
        current = new ArrayList<>();
        currentWidth = 0;
      }
      current.add(image);
      currentWidth += (current.size() == 1 ? 0 : 6.0) + imageWidth;
    }
    if (!current.isEmpty()) {
      rows.add(current);
    }
    return rows;
  }

  private void appendInlineImageTableRow(
      XWPFDocument document, List<RowImage> images, HtmlRenderContext context) throws Exception {
    var table = document.createTable(1, images.size());
    configureInlineImageTable(table);
    XWPFTableRow row = table.getRow(0);
    int cellIndex = 0;
    for (RowImage image : images) {
      if (isDuplicateImage(image.element(), context)) {
        continue;
      }
      XWPFTableCell cell = row.getCell(cellIndex++);
      configureInlineImageCell(cell);
      XWPFParagraph paragraph = firstCellParagraph(cell);
      paragraph.setAlignment(ParagraphAlignment.CENTER);
      if (!appendHtmlImageRun(paragraph, image.element(), context)) {
        cellIndex--;
      }
    }
  }

  private void configureInlineImageTable(XWPFTable table) {
    table.setWidth("100%");
    var ctTable = table.getCTTbl();
    var properties = ctTable.getTblPr() == null ? ctTable.addNewTblPr() : ctTable.getTblPr();
    var width = properties.isSetTblW() ? properties.getTblW() : properties.addNewTblW();
    width.setType(STTblWidth.PCT);
    width.setW(BigInteger.valueOf(5000));
    var borders = properties.isSetTblBorders() ? properties.getTblBorders() : properties.addNewTblBorders();
    borders.addNewTop().setVal(STBorder.NIL);
    borders.addNewBottom().setVal(STBorder.NIL);
    borders.addNewLeft().setVal(STBorder.NIL);
    borders.addNewRight().setVal(STBorder.NIL);
    borders.addNewInsideH().setVal(STBorder.NIL);
    borders.addNewInsideV().setVal(STBorder.NIL);
    table.setCellMargins(0, 0, 0, 0);
  }

  private void configureInlineImageCell(XWPFTableCell cell) {
    cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
    var ctCell = cell.getCTTc();
    var properties = ctCell.isSetTcPr() ? ctCell.getTcPr() : ctCell.addNewTcPr();
    properties.addNewVAlign().setVal(STVerticalJc.CENTER);
  }

  private XWPFParagraph firstCellParagraph(XWPFTableCell cell) {
    if (!cell.getParagraphs().isEmpty()) {
      XWPFParagraph paragraph = cell.getParagraphs().getFirst();
      paragraph.setSpacingBefore(0);
      paragraph.setSpacingAfter(0);
      paragraph.setIndentationFirstLine(0);
      return paragraph;
    }
    return cell.addParagraph();
  }

  private void appendInlineImageRowRuns(
      XWPFParagraph paragraph, Element element, HtmlRenderContext context)
      throws Exception {
    var images = descendantElements(element, "img");
    if (images.isEmpty()) {
      return;
    }
    var sizes = new ArrayList<ImageSize>();
    for (Element image : images) {
      if (isDuplicateImage(image, context)) {
        continue;
      }
      ImageBytes bytes = htmlImageBytes(image, context.imagesById());
      if (bytes != null) {
        sizes.add(wordImageSize(bytes.bytes(), htmlImageDisplaySize(image)));
      }
    }
    for (Element image : images) {
      if (appendHtmlImageRun(paragraph, image, context)) {
        paragraph.createRun().setText(" ");
      }
    }
  }

  private boolean containsElement(Element element, Set<String> tags) {
    for (String tag : tags) {
      if (element.getElementsByTagName(tag).getLength() > 0) {
        return true;
      }
    }
    return false;
  }

  private boolean containsInlineImageRow(Element element) {
    var nodes = element.getElementsByTagName("*");
    for (int index = 0; index < nodes.getLength(); index++) {
      if (nodes.item(index) instanceof Element child && hasClass(child, "inline-image-row")) {
        return true;
      }
    }
    return false;
  }

  private void appendInlineRuns(XWPFParagraph paragraph, Node node, boolean bold, int size) {
    if (node.getNodeType() == Node.TEXT_NODE) {
      String text = cleanWordText(node.getTextContent());
      if (!text.isBlank()) {
        if (bold && shouldKeepImportedBold(text)) {
          appendRun(paragraph, text, true, size);
        } else {
          appendStyledText(paragraph, text, false, size);
        }
      }
      return;
    }
    if (!(node instanceof Element element)) {
      return;
    }
    if (hasEditorOnlyClass(element)) {
      return;
    }
    String tag = element.getTagName().toLowerCase(java.util.Locale.ROOT);
    boolean childBold = bold || tag.equals("strong") || tag.equals("b");
    if (tag.equals("br")) {
      paragraph.createRun().addBreak();
      return;
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      appendInlineRuns(paragraph, child, childBold, size);
    }
  }

  private void appendInlineRunsWithImages(
      XWPFParagraph paragraph,
      Node node,
      HtmlRenderContext context,
      boolean bold,
      int size)
      throws Exception {
    if (node.getNodeType() == Node.TEXT_NODE) {
      String text = cleanWordText(node.getTextContent());
      if (!text.isBlank()) {
        if (bold && shouldKeepImportedBold(text)) {
          appendRun(paragraph, text, true, size);
        } else {
          appendStyledText(paragraph, text, false, size);
        }
      }
      return;
    }
    if (!(node instanceof Element element)) {
      return;
    }
    if (hasEditorOnlyClass(element)) {
      return;
    }
    String tag = element.getTagName().toLowerCase(java.util.Locale.ROOT);
    if (hasClass(element, "inline-image-row")) {
      appendInlineImageRowRuns(paragraph, element, context);
      return;
    }
    if (tag.equals("img")) {
      if (appendHtmlImageRun(paragraph, element, context)) {
        paragraph.createRun().setText(" ");
      }
      return;
    }
    boolean childBold = bold || tag.equals("strong") || tag.equals("b");
    if (tag.equals("br")) {
      paragraph.createRun().addBreak();
      return;
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      appendInlineRunsWithImages(paragraph, child, context, childBold, size);
    }
  }

  private boolean hasEditorOnlyClass(Element element) {
    String className = element.getAttribute("class");
    return List.of(
            "inline-image-resize-handle",
            "inline-image-drop-marker",
            "inline-image-uploading")
        .stream()
        .anyMatch(className::contains);
  }

  private boolean hasClass(Element element, String expected) {
    String className = element.getAttribute("class");
    if (className == null || className.isBlank()) {
      return false;
    }
    return List.of(className.split("\\s+")).contains(expected);
  }

  private List<Element> descendantElements(Element element, String tagName) {
    var elements = new ArrayList<Element>();
    var nodes = element.getElementsByTagName(tagName);
    for (int index = 0; index < nodes.getLength(); index++) {
      if (nodes.item(index) instanceof Element child) {
        elements.add(child);
      }
    }
    return elements;
  }

  private void appendWordParagraph(XWPFDocument document, String text, boolean bold, int size) {
    appendWordParagraph(document, text, bold, size, false);
  }

  private void appendWordParagraph(
      XWPFDocument document, String text, boolean bold, int size, boolean centered) {
    String cleaned = cleanWordText(text);
    if (cleaned.isBlank()) {
      return;
    }
    var paragraph = document.createParagraph();
    applyParagraphStyle(paragraph, bold, centered);
    appendStyledText(paragraph, cleaned, bold, size);
  }

  private void appendBlankWordParagraph(XWPFDocument document) {
    var paragraph = document.createParagraph();
    paragraph.setSpacingAfter(80);
    paragraph.createRun().setText(" ");
  }

  private void applyParagraphStyle(XWPFParagraph paragraph, boolean heading, boolean centered) {
    if (centered) {
      paragraph.setAlignment(ParagraphAlignment.CENTER);
    }
    paragraph.setSpacingBefore(heading ? 140 : 40);
    paragraph.setSpacingAfter(heading ? 120 : 80);
    if (!heading && !centered) {
      paragraph.setIndentationFirstLine(BODY_FIRST_LINE_INDENT_TWIPS);
    }
  }

  private void appendStyledText(XWPFParagraph paragraph, String text, boolean defaultBold, int size) {
    String cleaned = cleanWordText(text);
    if (cleaned.isBlank()) {
      return;
    }
    if (defaultBold) {
      appendRun(paragraph, cleaned, true, size);
      return;
    }
    int index = 0;
    while (index < cleaned.length()) {
      Match phrase = nextImportantPhrase(cleaned, index);
      Match causeLabel = nextCauseLabel(cleaned, index);
      Match match = firstMatch(phrase, causeLabel);
      if (match == null) {
        appendRun(paragraph, cleaned.substring(index), false, size);
        break;
      }
      if (match == causeLabel) {
        if (match.start() > index) {
          appendRun(paragraph, cleaned.substring(index, match.start()), false, size);
        }
        appendRun(paragraph, cleaned.substring(match.start(), match.end()), false, size);
        int emphasisEnd = sentenceEnd(cleaned, match.end());
        appendReasonAfterLabel(paragraph, cleaned.substring(match.end(), emphasisEnd), size);
        index = emphasisEnd;
        continue;
      }
      if (match.start() > index) {
        appendRun(paragraph, cleaned.substring(index, match.start()), false, size);
      }
      int emphasisEnd = sentenceEnd(cleaned, match.start());
      appendRun(paragraph, cleaned.substring(match.start(), emphasisEnd), true, size);
      index = emphasisEnd;
    }
  }

  private Match firstMatch(Match first, Match second) {
    if (first == null) return second;
    if (second == null) return first;
    return first.start() <= second.start() ? first : second;
  }

  private boolean shouldKeepImportedBold(String text) {
    String cleaned = cleanWordText(text);
    if (cleaned.isBlank()) {
      return false;
    }
    if (METRIC_COMPARISON_EMPHASIS.matcher(cleaned).matches()
        && IMPORTANT_REASON_PHRASES.stream().noneMatch(cleaned::contains)) {
      return false;
    }
    return CAUSE_LABEL.matcher(cleaned).find()
            && IMPORTANT_REASON_PHRASES.stream().noneMatch(cleaned::contains)
        ? false
        : CAUSE_LABEL_PHRASES.stream().noneMatch(cleaned::contains);
  }

  private void appendReasonAfterLabel(XWPFParagraph paragraph, String text, int size) {
    if (text == null || text.isEmpty()) {
      return;
    }
    int leadingEnd = 0;
    while (leadingEnd < text.length() && Character.isWhitespace(text.charAt(leadingEnd))) {
      leadingEnd++;
    }
    if (leadingEnd > 0) {
      appendRun(paragraph, text.substring(0, leadingEnd), false, size);
    }
    String cause = text.substring(leadingEnd);
    if (cause.isBlank()) {
      return;
    }
    if (isMetricOnlyText(cause)) {
      appendRun(paragraph, cause, false, size);
      return;
    }
    appendRun(paragraph, cause, true, size);
  }

  private boolean isMetricOnlyText(String text) {
    String cleaned = cleanWordText(text);
    return METRIC_COMPARISON_EMPHASIS.matcher(cleaned).matches()
        && IMPORTANT_REASON_PHRASES.stream().noneMatch(cleaned::contains);
  }

  private Match nextCauseLabel(String text, int startIndex) {
    Matcher matcher = CAUSE_LABEL.matcher(text);
    if (!matcher.find(startIndex)) {
      return null;
    }
    return new Match(matcher.start(), matcher.end(), matcher.group());
  }

  private Match nextImportantPhrase(String text, int startIndex) {
    Match best = null;
    for (String phrase : IMPORTANT_REASON_PHRASES) {
      int start = text.indexOf(phrase, startIndex);
      if (start < 0) {
        continue;
      }
      Match candidate = new Match(start, start + phrase.length(), phrase);
      if (best == null || candidate.start() < best.start()) {
        best = candidate;
      }
    }
    return best;
  }

  private int sentenceEnd(String text, int start) {
    for (int index = start; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character == '。' || character == '；' || character == ';' || character == '\n') {
        return index + 1;
      }
    }
    return text.length();
  }

  private void appendRun(XWPFParagraph paragraph, String text, boolean bold, int size) {
    if (text == null || text.isEmpty()) {
      return;
    }
    var run = paragraph.createRun();
    run.setText(text);
    run.setBold(bold);
    run.setFontFamily(bold ? "SimHei" : "SimSun");
    run.setFontSize(size);
  }

  private void appendHtmlTable(XWPFDocument document, Element tableElement, HtmlRenderContext context)
      throws Exception {
    appendHtmlChildren(document, tableElement, context);
  }

  private void appendHtmlImage(XWPFDocument document, Element image, HtmlRenderContext context)
      throws Exception {
    Optional<DisplaySize> displaySize = htmlImageDisplaySize(image);
    if (!claimImage(image, context)) return;
    ImageBytes bytes = htmlImageBytes(image, context.imagesById());
    if (bytes == null) return;
    appendWordImage(document, bytes.bytes(), bytes.mediaType(), bytes.name(), displaySize);
  }

  private boolean appendHtmlImageRun(XWPFParagraph paragraph, Element image, HtmlRenderContext context)
      throws Exception {
    return appendHtmlImageRun(paragraph, image, context, 1.0);
  }

  private boolean appendHtmlImageRun(
      XWPFParagraph paragraph, Element image, HtmlRenderContext context, double scale)
      throws Exception {
    Optional<DisplaySize> displaySize = htmlImageDisplaySize(image);
    if (!claimImage(image, context)) return false;
    ImageBytes bytes = htmlImageBytes(image, context.imagesById());
    if (bytes == null) return false;
    appendWordImageRun(paragraph, bytes.bytes(), bytes.mediaType(), bytes.name(), displaySize, scale);
    return true;
  }

  private ImageBytes htmlImageBytes(Element image, Map<String, ReportImage> imagesById) {
    String src = image.getAttribute("src");
    String fileId = image.getAttribute("data-file-id");
    ReportImage stored = imagesById.get(fileId);
    if (stored != null) {
      return new ImageBytes(stored.bytes(), stored.mediaType(), stored.name());
    }
    if (!src.startsWith("data:image/")) return null;
    int comma = src.indexOf(',');
    int semicolon = src.indexOf(';');
    if (comma < 0 || semicolon < 0 || semicolon > comma) return null;
    String mediaType = src.substring("data:".length(), semicolon);
    byte[] bytes = Base64.getDecoder().decode(src.substring(comma + 1));
    return new ImageBytes(bytes, mediaType, "pasted-image");
  }

  private boolean isDuplicateImage(Element image, HtmlRenderContext context) {
    String identity = imageIdentity(image);
    return identity != null && context.renderedImageIds().contains(identity);
  }

  private boolean claimImage(Element image, HtmlRenderContext context) {
    String identity = imageIdentity(image);
    return identity == null || context.renderedImageIds().add(identity);
  }

  private String imageIdentity(Element image) {
    String fileId = image.getAttribute("data-file-id");
    if (fileId != null && !fileId.isBlank()) {
      return "file:" + fileId.trim();
    }
    String src = image.getAttribute("src");
    if (src != null && src.startsWith("data:image/")) {
      return "src:" + src;
    }
    return null;
  }

  private void appendWordImage(
      XWPFDocument document, byte[] bytes, String mediaType, String imageName) throws Exception {
    appendWordImage(document, bytes, mediaType, imageName, Optional.empty());
  }

  private void appendWordImage(
      XWPFDocument document,
      byte[] bytes,
      String mediaType,
      String imageName,
      Optional<DisplaySize> displaySize)
      throws Exception {
    int type =
        "image/png".equals(mediaType)
            ? XWPFDocument.PICTURE_TYPE_PNG
            : XWPFDocument.PICTURE_TYPE_JPEG;
    var paragraph = document.createParagraph();
    paragraph.setAlignment(ParagraphAlignment.CENTER);
    paragraph.setSpacingBefore(80);
    paragraph.setSpacingAfter(120);
    ImageSize size = wordImageSize(bytes, displaySize);
    paragraph
        .createRun()
        .addPicture(
            new ByteArrayInputStream(bytes), type, imageName, size.widthEmu(), size.heightEmu());
  }

  private void appendWordImageRun(
      XWPFParagraph paragraph,
      byte[] bytes,
      String mediaType,
      String imageName,
      Optional<DisplaySize> displaySize)
      throws Exception {
    appendWordImageRun(paragraph, bytes, mediaType, imageName, displaySize, 1.0);
  }

  private void appendWordImageRun(
      XWPFParagraph paragraph,
      byte[] bytes,
      String mediaType,
      String imageName,
      Optional<DisplaySize> displaySize,
      double scale)
      throws Exception {
    int type =
        "image/png".equals(mediaType)
            ? XWPFDocument.PICTURE_TYPE_PNG
            : XWPFDocument.PICTURE_TYPE_JPEG;
    ImageSize size = scaleImageSize(wordImageSize(bytes, displaySize), scale);
    XWPFRun run = paragraph.createRun();
    run.addPicture(
        new ByteArrayInputStream(bytes), type, imageName, size.widthEmu(), size.heightEmu());
  }

  private ImageSize scaleImageSize(ImageSize size, double scale) {
    if (scale >= 0.999) return size;
    return new ImageSize(
        Math.max(1, (int) Math.round(size.widthEmu() * scale)),
        Math.max(1, (int) Math.round(size.heightEmu() * scale)));
  }

  private ImageSize wordImageSize(byte[] bytes) throws IOException {
    return wordImageSize(bytes, Optional.empty());
  }

  private ImageSize wordImageSize(byte[] bytes, Optional<DisplaySize> displaySize)
      throws IOException {
    BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
    if (displaySize.isPresent()) {
      return wordImageSizeFromDisplaySize(displaySize.get(), buffered);
    }
    if (buffered == null || buffered.getWidth() <= 0 || buffered.getHeight() <= 0) {
      return new ImageSize(Units.toEMU(1), Units.toEMU(1));
    }
    return constrainImageToPageWidth(
        cssPixelsToPoints(buffered.getWidth()), cssPixelsToPoints(buffered.getHeight()));
  }

  private ImageSize wordImageSizeFromDisplaySize(DisplaySize displaySize, BufferedImage buffered) {
    double width = displaySize.widthPoints();
    double height = displaySize.heightPoints();
    if (buffered != null && buffered.getWidth() > 0 && buffered.getHeight() > 0) {
      double ratio = (double) buffered.getWidth() / (double) buffered.getHeight();
      if (width > 0) {
        height = width / ratio;
      } else if (height > 0) {
        width = height * ratio;
      }
    }
    if (width <= 0 && height <= 0) {
      return buffered == null
          ? new ImageSize(Units.toEMU(1), Units.toEMU(1))
          : constrainImageToPageWidth(
              cssPixelsToPoints(buffered.getWidth()), cssPixelsToPoints(buffered.getHeight()));
    }
    if (width <= 0 || height <= 0) {
      return new ImageSize(Units.toEMU(1), Units.toEMU(1));
    }
    return constrainImageToPageWidth(width, height);
  }

  private ImageSize constrainImageToPageWidth(double widthPoints, double heightPoints) {
    double width = Math.max(1, widthPoints);
    double height = Math.max(1, heightPoints);
    if (width > WORD_PAGE_IMAGE_MAX_WIDTH_POINTS) {
      double scale = WORD_PAGE_IMAGE_MAX_WIDTH_POINTS / width;
      width *= scale;
      height *= scale;
    }
    return new ImageSize(Units.toEMU(width), Units.toEMU(height));
  }

  private Optional<DisplaySize> htmlImageDisplaySize(Element image) {
    Optional<DisplaySize> imageSize = displaySizeFromElement(image);
    if (imageSize.isPresent()) {
      return imageSize;
    }
    Node parent = image.getParentNode();
    if (parent instanceof Element parentElement) {
      return displaySizeFromElement(parentElement);
    }
    return Optional.empty();
  }

  private Optional<DisplaySize> displaySizeFromElement(Element element) {
    Double width =
        firstCssPixels(
            element.getAttribute("data-display-width"),
            element.getAttribute("width"),
            cssProperty(element.getAttribute("style"), "width"));
    Double height =
        firstCssPixels(
            element.getAttribute("data-display-height"),
            element.getAttribute("height"),
            cssProperty(element.getAttribute("style"), "height"));
    if (width == null || height == null) {
      return Optional.empty();
    }
    return Optional.of(new DisplaySize(cssPixelsToPoints(width), cssPixelsToPoints(height)));
  }

  private Double firstCssPixels(String... values) {
    for (String value : values) {
      Double parsed = cssPixels(value);
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  private String cssProperty(String style, String name) {
    if (style == null || style.isBlank()) {
      return "";
    }
    for (String part : style.split(";")) {
      int colon = part.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = part.substring(0, colon).trim();
      if (key.equalsIgnoreCase(name)) {
        return part.substring(colon + 1).trim();
      }
    }
    return "";
  }

  private Double cssPixels(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    var matcher = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(px|pt|in|cm|mm)?$", Pattern.CASE_INSENSITIVE)
        .matcher(value.trim());
    if (!matcher.matches()) {
      return null;
    }
    double numeric = Double.parseDouble(matcher.group(1));
    if (numeric <= 0) {
      return null;
    }
    String unit = matcher.group(2) == null ? "px" : matcher.group(2).toLowerCase(java.util.Locale.ROOT);
    return switch (unit) {
      case "pt" -> numeric / 0.75;
      case "in" -> numeric * 96;
      case "cm" -> numeric / 2.54 * 96;
      case "mm" -> numeric / 25.4 * 96;
      default -> numeric;
    };
  }

  private double cssPixelsToPoints(double pixels) {
    return pixels * 0.75;
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
      List<DocTable> tables = docTables(range);
      Set<Integer> emittedTableIndexes = new java.util.HashSet<>();
      for (int index = 0; index < range.numParagraphs(); index++) {
        Paragraph paragraph = range.getParagraph(index);
        int tableIndex = docTableIndex(tables, paragraph);
        if (tableIndex >= 0) {
          if (emittedTableIndexes.add(tableIndex)) {
            appendDocTable(html, document.getPicturesTable(), tables.get(tableIndex).table());
          }
          continue;
        }
        appendDocParagraph(html, document.getPicturesTable(), paragraph);
      }
      html.append("</div>");
      return html.toString();
    }
  }

  private List<DocTable> docTables(Range range) {
    List<DocTable> tables = new ArrayList<>();
    TableIterator iterator = new TableIterator(range);
    while (iterator.hasNext()) {
      Table table = iterator.next();
      tables.add(new DocTable(table, table.getStartOffset(), table.getEndOffset()));
    }
    return tables;
  }

  private int docTableIndex(List<DocTable> tables, Paragraph paragraph) {
    for (int index = 0; index < tables.size(); index++) {
      DocTable table = tables.get(index);
      if (paragraph.getStartOffset() >= table.startOffset()
          && paragraph.getEndOffset() <= table.endOffset()) {
        return index;
      }
    }
    return -1;
  }

  private void appendDocTable(StringBuilder html, PicturesTable picturesTable, Table table) {
    html.append("<table class=\"word-table\">");
    for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
      TableRow row = table.getRow(rowIndex);
      html.append("<tr>");
      for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
        TableCell cell = row.getCell(cellIndex);
        html.append("<td>");
        appendDocRange(html, picturesTable, cell);
        html.append("</td>");
      }
      html.append("</tr>");
    }
    html.append("</table>");
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
    html.append("<table class=\"word-table\">");
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
        appendStyledRun(text, runText, run.isBold(), run.getFontSize(), run.getFontFamily());
      }
      for (XWPFPicture picture : run.getEmbeddedPictures()) {
        XWPFPictureData data = picture.getPictureData();
        if (data != null) {
          appendImage(
              text,
              data.getData(),
              data.getFileName(),
              data.getPackagePart().getContentType(),
              displaySizeFromDocxPicture(picture));
          wroteContent = true;
        }
      }
    }
    if (appendBufferedParagraph(html, text, paragraphStyle(paragraph))) {
      return;
    }
    if (!wroteContent) {
      appendParagraph(html, paragraph.getText(), paragraphStyle(paragraph));
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
        Picture picture = picturesTable.extractPicture(run, false);
        if (picture != null) {
          appendImage(
              text,
              picture.getContent(),
              picture.suggestFullFileName(),
              picture.getMimeType(),
              displaySizeFromDocPicture(picture));
          wroteContent = true;
        }
      } else {
        String runText = cleanWordText(run.text());
        if (!runText.isBlank()) {
          appendStyledRun(text, runText, run.isBold(), run.getFontSize() / 2, null);
        }
      }
    }
    if (!appendBufferedParagraph(html, text, paragraphStyle(paragraph)) && !wroteContent) {
      appendParagraph(html, paragraph.text(), paragraphStyle(paragraph));
    }
  }

  private Optional<DisplaySize> displaySizeFromDocxPicture(XWPFPicture picture) {
    try {
      var ext = picture.getCTPicture().getSpPr().getXfrm().getExt();
      if (ext == null || ext.getCx() <= 0 || ext.getCy() <= 0) {
        return Optional.empty();
      }
      return Optional.of(new DisplaySize(emuToPoints(ext.getCx()), emuToPoints(ext.getCy())));
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private Optional<DisplaySize> displaySizeFromDocPicture(Picture picture) {
    int widthTwips = picture.getDxaGoal();
    int heightTwips = picture.getDyaGoal();
    if (widthTwips <= 0 || heightTwips <= 0) {
      return Optional.empty();
    }
    double widthPoints = twipsToPoints(widthTwips);
    double heightPoints = twipsToPoints(heightTwips);
    Optional<ImageDimensions> dimensions = imageDimensions(picture.getContent());
    if (dimensions.isPresent()) {
      ImageDimensions image = dimensions.get();
      double naturalRatio = (double) image.width() / (double) image.height();
      if (naturalRatio > 0) {
        heightPoints = widthPoints / naturalRatio;
      }
      double maxWidth = naturalRatio < 1 ? 160 : WORD_PAGE_IMAGE_MAX_WIDTH_POINTS;
      if (widthPoints > maxWidth) {
        double scale = maxWidth / widthPoints;
        widthPoints *= scale;
        heightPoints *= scale;
      }
    }
    return Optional.of(new DisplaySize(widthPoints, heightPoints));
  }

  private Optional<ImageDimensions> imageDimensions(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return Optional.empty();
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
        return Optional.empty();
      }
      return Optional.of(new ImageDimensions(image.getWidth(), image.getHeight()));
    } catch (IOException exception) {
      return Optional.empty();
    }
  }

  private double emuToPoints(long emu) {
    return emu / 12700.0;
  }

  private double twipsToPoints(int twips) {
    return twips / 20.0;
  }

  private void appendImage(
      StringBuilder html,
      byte[] bytes,
      String name,
      String mediaType,
      Optional<DisplaySize> displaySize) {
    if (bytes == null || bytes.length == 0) {
      return;
    }
    String type = mediaType == null || mediaType.isBlank() ? "image/png" : mediaType;
    html.append("<span class=\"word-inline-image\"><img alt=\"")
        .append(escapeHtml(name == null ? "Word image" : name))
        .append("\" src=\"data:")
        .append(escapeHtml(type))
        .append(";base64,")
        .append(Base64.getEncoder().encodeToString(bytes))
        .append("\"");
    displaySize.ifPresent(size -> appendDisplaySizeAttributes(html, size));
    html.append(" /></span>");
  }

  private void appendDisplaySizeAttributes(StringBuilder html, DisplaySize size) {
    double widthPixels = pointsToCssPixels(size.widthPoints());
    double heightPixels = pointsToCssPixels(size.heightPoints());
    if (widthPixels <= 0 || heightPixels <= 0) {
      return;
    }
    String width = formatCssPixelValue(widthPixels);
    String height = formatCssPixelValue(heightPixels);
    html.append(" data-display-width=\"")
        .append(width)
        .append("\" data-display-height=\"")
        .append(height)
        .append("\" width=\"")
        .append(Math.round(widthPixels))
        .append("\" height=\"")
        .append(Math.round(heightPixels))
        .append("\" style=\"width:")
        .append(width)
        .append("px;height:")
        .append(height)
        .append("px\"");
  }

  private double pointsToCssPixels(double points) {
    return points / 0.75;
  }

  private String formatCssPixelValue(double value) {
    if (Math.abs(value - Math.rint(value)) < 0.01) {
      return String.valueOf(Math.round(value));
    }
    return String.format(java.util.Locale.ROOT, "%.2f", value);
  }

  private boolean appendBufferedParagraph(StringBuilder html, StringBuilder text, String style) {
    String cleaned = cleanWordText(text.toString());
    text.setLength(0);
    if (cleaned.isBlank()) {
      return false;
    }
    appendHtmlParagraphs(html, cleaned, style);
    return true;
  }

  private void appendParagraph(StringBuilder html, String text) {
    appendParagraph(html, text, "");
  }

  private void appendParagraph(StringBuilder html, String text, String style) {
    String cleaned = cleanWordText(text);
    if (cleaned.isBlank()) {
      return;
    }
    appendPlainTextParagraphs(html, cleaned, style);
  }

  private void appendHtmlParagraph(StringBuilder html, String contentHtml, String style) {
    if (contentHtml == null || contentHtml.isBlank()) {
      return;
    }
    appendHtmlParagraphs(html, contentHtml, style);
  }

  private void appendPlainTextParagraphs(StringBuilder html, String text, String style) {
    for (String paragraph : splitCauseParagraphs(text)) {
      if (paragraph.isBlank()) {
        continue;
      }
      html.append("<p");
      if (style != null && !style.isBlank()) {
        html.append(" style=\"").append(escapeHtml(style)).append("\"");
      }
      html.append(">");
      appendText(html, paragraph);
      html.append("</p>");
    }
  }

  private void appendHtmlParagraphs(StringBuilder html, String contentHtml, String style) {
    List<String> paragraphs = splitCauseParagraphs(contentHtml);
    for (String paragraph : paragraphs) {
      if (paragraph.isBlank()) {
        continue;
      }
      appendSingleHtmlParagraph(html, paragraph, style);
    }
  }

  private void appendSingleHtmlParagraph(StringBuilder html, String contentHtml, String style) {
    boolean imageOnly = isWordImageOnlyParagraph(contentHtml);
    html.append("<p");
    if (imageOnly) {
      html.append(" class=\"word-image-paragraph\"");
    }
    if (style != null && !style.isBlank()) {
      html.append(" style=\"").append(escapeHtml(style)).append("\"");
    }
    html.append(">");
    html.append(contentHtml);
    html.append("</p>");
  }

  private boolean isWordImageOnlyParagraph(String contentHtml) {
    if (contentHtml == null || !contentHtml.contains("word-inline-image")) {
      return false;
    }
    String withoutImages = WORD_INLINE_IMAGE_SPAN.matcher(contentHtml).replaceAll("");
    String withoutLineBreaks = HTML_LINE_BREAK.matcher(withoutImages).replaceAll("");
    return withoutLineBreaks.replace("&nbsp;", "").trim().isBlank();
  }

  private List<String> splitCauseParagraphs(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return CAUSE_LABEL_BOUNDARY
        .splitAsStream(value)
        .map(String::trim)
        .filter(part -> !part.isBlank())
        .toList();
  }

  private void appendStyledRun(
      StringBuilder html, String text, boolean bold, int fontSize, String fontFamily) {
    String cleaned = cleanWordText(text);
    if (cleaned.isBlank()) {
      return;
    }
    StringBuilder style = new StringBuilder();
    if (bold) {
      style.append("font-weight:700;");
    }
    if (fontSize > 0) {
      style.append("font-size:").append(fontSize).append("pt;");
    }
    if (fontFamily != null && !fontFamily.isBlank()) {
      style.append("font-family:").append(cssFontFamily(fontFamily)).append(";");
    }
    if (style.isEmpty()) {
      appendText(html, cleaned);
      return;
    }
    html.append("<span style=\"")
        .append(escapeHtml(style.toString()))
        .append("\">");
    appendText(html, cleaned);
    html.append("</span>");
  }

  private String cssFontFamily(String value) {
    return "'"
        + value
            .replace("\\", "")
            .replace("'", "")
            .replace("\"", "")
            .trim()
        + "'";
  }

  private String paragraphStyle(XWPFParagraph paragraph) {
    ParagraphAlignment alignment = paragraph.getAlignment();
    if (alignment == ParagraphAlignment.CENTER) {
      return "text-align:center;";
    }
    if (alignment == ParagraphAlignment.RIGHT) {
      return "text-align:right;";
    }
    return "";
  }

  private String paragraphStyle(Paragraph paragraph) {
    int justification = paragraph.getJustification();
    if (justification == 1) {
      return "text-align:center;";
    }
    if (justification == 2) {
      return "text-align:right;";
    }
    return "";
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
    return normalizeReportWhitespace(stripUnsupportedControlCharacters(normalized));
  }

  private String normalizeReportWhitespace(String value) {
    String normalized = value.replace('\u00a0', ' ').replace('\u3000', ' ');
    normalized = normalized.replaceAll("[ \\t\\x0B\\f]+", " ");
    normalized =
        java.util.Arrays.stream(normalized.split("\\n", -1))
            .map(String::trim)
            .collect(Collectors.joining("\n"));
    normalized = normalized.replaceAll("\\n{3,}", "\n\n");
    return normalized.trim();
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
    IllegalArgumentException exception =
        new IllegalArgumentException("Word file could not be read");
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
      Map<String, ReportImage> imagesById =
          images.stream()
              .collect(Collectors.toMap(ReportImage::fileId, Function.identity(), (a, b) -> a));
      var title = document.createParagraph();
      title.setAlignment(ParagraphAlignment.CENTER);
      title.setSpacingAfter(240);
      var titleRun = title.createRun();
      titleRun.setText(sections.title());
      titleRun.setBold(true);
      titleRun.setFontFamily("SimHei");
      titleRun.setFontSize(18);
      String situation = stripLeadingSectionHeading(sections.situation(), "一、情况说明");
      String analysis = stripLeadingSectionHeading(sections.analysis(), "二、排查分析");
      String rectification = stripLeadingSectionHeading(sections.rectification(), "三、整改小结");
      var context = new HtmlRenderContext(imagesById, new java.util.HashSet<>());
      addSection(document, "一、情况说明", situation, context);
      addSection(document, "二、排查分析", analysis, context);
      addSection(document, "三、整改小结", rectification, context);
      Set<String> inlineIds =
          inlineFileIds(situation, analysis, rectification);
      for (ReportImage image : images) {
        if (inlineIds.contains(image.fileId()) || context.renderedImageIds().contains("file:" + image.fileId())) continue;
        appendWordImage(document, image.bytes(), image.mediaType(), image.name());
      }
      document.write(output);
      return output.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("Word report could not be generated", exception);
    }
  }

  private void addSection(
      XWPFDocument document, String heading, String content, HtmlRenderContext context)
      throws Exception {
    var headingParagraph = document.createParagraph();
    applyParagraphStyle(headingParagraph, true, false);
    var headingRun = headingParagraph.createRun();
    headingRun.setText(heading);
    headingRun.setBold(true);
    headingRun.setFontFamily("SimHei");
    headingRun.setFontSize(14);
    if (HTML_TAG.matcher(content).find()) {
      appendHtmlFragment(document, content, context);
      return;
    }
    for (String line : content.split("\\R", -1)) {
      if (line.isBlank()) {
        appendBlankWordParagraph(document);
        continue;
      }
      var paragraph = document.createParagraph();
      applyParagraphStyle(paragraph, false, false);
      appendStyledText(paragraph, line, false, 12);
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
      writer.paragraph(plainText(stripLeadingSectionHeading(sections.situation(), "一、情况说明")));
      writer.heading("二、排查分析");
      writer.paragraph(plainText(stripLeadingSectionHeading(sections.analysis(), "二、排查分析")));
      writer.heading("三、整改小结");
      writer.paragraph(plainText(stripLeadingSectionHeading(sections.rectification(), "三、整改小结")));
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

  private Set<String> inlineFileIds(String... contents) {
    return java.util.Arrays.stream(contents)
        .flatMap(content -> INLINE_FILE_ID.matcher(content).results())
        .map(result -> result.group(1))
        .collect(Collectors.toSet());
  }

  private String stripLeadingSectionHeading(String content, String heading) {
    if (content == null || content.isBlank()) {
      return "";
    }
    String cleaned = content.stripLeading();
    String section = sectionHeadingPattern(heading);
    if (HTML_TAG.matcher(cleaned).find()) {
      String blockHeading =
          "(?is)^\\s*(?:<br\\s*/?>\\s*)*(?:<(?:h[1-6]|p|div)[^>]*>\\s*)"
              + section
              + "\\s*[：:]?\\s*(?:</(?:h[1-6]|p|div)>\\s*)";
      String inlineHeading =
          "(?is)^\\s*(?:<br\\s*/?>\\s*)*(?:<(?:p|div)[^>]*>\\s*)"
              + section
              + "\\s*[：:]\\s*";
      String stripped = cleaned.replaceFirst(blockHeading, "").replaceFirst(inlineHeading, "<p>");
      return stripped.stripLeading();
    }
    return cleaned.replaceFirst("(?s)^\\s*" + section + "\\s*[：:]?\\s*", "");
  }

  private String sectionHeadingPattern(String heading) {
    String normalized = heading.replaceAll("[\\s、.．:：]", "");
    if (normalized.length() < 2) {
      return Pattern.quote(heading);
    }
    String number = Pattern.quote(normalized.substring(0, 1));
    String title = Pattern.quote(normalized.substring(1));
    return number + "\\s*[、.．]?\\s*" + title;
  }

  private String plainText(String content) {
    if (content == null || content.isBlank() || !HTML_TAG.matcher(content).find()) {
      return content == null ? "" : content;
    }
    try {
      String wrapped = "<root>" + normalizeHtml(content) + "</root>";
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      var dom =
          factory
              .newDocumentBuilder()
              .parse(new ByteArrayInputStream(wrapped.getBytes(StandardCharsets.UTF_8)));
      return dom.getDocumentElement().getTextContent().trim();
    } catch (Exception exception) {
      return content.replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
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

  public record ReportImage(String fileId, String name, String mediaType, byte[] bytes) {
    public ReportImage {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private record Match(int start, int end, String phrase) {}

  private record ImageSize(int widthEmu, int heightEmu) {}

  private record ImageBytes(byte[] bytes, String mediaType, String name) {}

  private record RowImage(Element element, ImageSize size) {}

  private record DisplaySize(double widthPoints, double heightPoints) {}

  private record ImageDimensions(int width, int height) {}

  private record DocTable(Table table, int startOffset, int endOffset) {}

  private record HtmlRenderContext(
      Map<String, ReportImage> imagesById, Set<String> renderedImageIds) {}

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
