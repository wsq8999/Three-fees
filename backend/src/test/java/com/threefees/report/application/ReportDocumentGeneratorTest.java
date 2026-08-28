package com.threefees.report.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ai.application.AiServiceClient.ReportSections;
import com.threefees.report.application.ReportDocumentGenerator.ReportImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class ReportDocumentGeneratorTest {

  @Test
  void generatesRealChineseWordAndPdfDocuments() throws Exception {
    var generator = new ReportDocumentGenerator("");
    var sections = new ReportSections("江苏省物业电费稽核报告", "情况正文", "排查正文", "整改正文");

    var generated = generator.generate(sections, List.of());

    assertThat(generated.word()).startsWith((byte) 'P', (byte) 'K');
    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()));
        var extractor = new XWPFWordExtractor(word)) {
      assertThat(extractor.getText()).contains("江苏省物业电费稽核报告", "一、情况说明", "二、排查分析", "三、整改小结");
    }
    assertThat(generated.pdf()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F');
    try (var pdf = Loader.loadPDF(generated.pdf())) {
      assertThat(pdf.getNumberOfPages()).isEqualTo(1);
      assertThat(new PDFTextStripper().getText(pdf)).contains("江苏省物业电费稽核报告");
    }
  }

  @Test
  void extractsHistoricalWordTextFromGeneratedDocx() {
    var generator = new ReportDocumentGenerator("");
    var sections = new ReportSections("历史报告", "情况正文", "排查正文", "整改正文");

    var text =
        generator.extractWordText(generator.generate(sections, List.of()).word(), "历史报告.docx");

    assertThat(text).contains("历史报告", "情况正文", "排查正文", "整改正文");
  }

  @Test
  void keepsBasicWordStylesInHistoricalPreviewHtml() throws Exception {
    var generator = new ReportDocumentGenerator("");
    byte[] wordBytes;
    try (var document = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      var title = document.createParagraph();
      title.setAlignment(ParagraphAlignment.CENTER);
      var titleRun = title.createRun();
      titleRun.setText("陈堡花沈搬迁电费稽核说明");
      titleRun.setBold(true);
      titleRun.setFontSize(18);
      var body = document.createParagraph();
      body.createRun().setText("与标杆电量对比，差异情况");
      document.write(output);
      wordBytes = output.toByteArray();
    }

    String html = generator.extractWordPreviewHtml(wordBytes, "历史报告.docx");

    assertThat(html)
        .contains("陈堡花沈搬迁电费稽核说明", "与标杆电量对比，差异情况")
        .contains("text-align:center")
        .contains("font-weight:700")
        .contains("font-size:18pt");
  }

  @Test
  void keepsTablesInHistoricalPreviewHtml() throws Exception {
    var generator = new ReportDocumentGenerator("");
    byte[] wordBytes;
    try (var document = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      var table = document.createTable(1, 2);
      table.getRow(0).getCell(0).setText("表格左列");
      table.getRow(0).getCell(1).setText("表格右列");
      document.write(output);
      wordBytes = output.toByteArray();
    }

    String html = generator.extractWordPreviewHtml(wordBytes, "历史报告.docx");

    assertThat(html)
        .contains("<table class=\"word-table\">", "<tr>", "<td>")
        .contains("表格左列", "表格右列")
        .contains("</td>", "</tr>", "</table>");
  }

  @Test
  void splitsMultipleCauseSentencesIntoSeparatePreviewParagraphs() throws Exception {
    var generator = new ReportDocumentGenerator("");
    byte[] wordBytes;
    try (var document = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      var paragraph = document.createParagraph();
      paragraph
          .createRun()
          .setText(
              "本期电量同比超标原因：分摊比例变化。本期额定标杆超标原因：资管系统未及时更新。");
      document.write(output);
      wordBytes = output.toByteArray();
    }

    String html = generator.extractWordPreviewHtml(wordBytes, "历史报告.docx");

    assertThat(html)
        .contains("<p>本期电量同比超标原因：分摊比例变化。</p>")
        .contains("<p>本期额定标杆超标原因：资管系统未及时更新。</p>");
  }

  @Test
  void doesNotDuplicateSectionHeadingsWhenContentAlreadyContainsThem() throws Exception {
    var generator = new ReportDocumentGenerator("");
    var sections =
        new ReportSections(
            "陈堡花沈搬迁电费稽核说明",
            "一、情况说明：与标杆电量对比，差异情况",
            "<h2>二、排查分析</h2><p>排查正文</p>",
            "<p>三整改小结：整改正文</p>");

    var generated = generator.generate(sections, List.of());

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()));
        var extractor = new XWPFWordExtractor(word)) {
      String text = extractor.getText();
      assertThat(countOccurrences(text, "一、情况说明")).isEqualTo(1);
      assertThat(countOccurrences(text, "二、排查分析")).isEqualTo(1);
      assertThat(countOccurrences(text, "三、整改小结")).isEqualTo(1);
      assertThat(text).contains("与标杆电量对比，差异情况", "排查正文", "整改正文");
    }
  }

  @Test
  void keepsInlineEditedTextAndImageInGeneratedWord() throws Exception {
    var generator = new ReportDocumentGenerator("");
    String imageId = "inline-image-1";
    var sections =
        new ReportSections(
            "内嵌图片报告",
            "<div>情况可以编辑</div>",
            "<div>图片前文字</div><figure data-file-id=\""
                + imageId
                + "\"><img data-file-id=\""
                + imageId
                + "\" src=\"/api/v1/files/inline-image-1?inline=true\"></figure>"
                + "<div>图片后文字</div>",
            "<div>整改内容</div>");
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", imageBytes);

    var generated =
        generator.generate(
            sections,
            List.of(
                new ReportImage(imageId, "evidence.png", "image/png", imageBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()));
        var extractor = new XWPFWordExtractor(word)) {
      assertThat(extractor.getText())
          .contains("情况可以编辑", "图片前文字", "图片后文字", "整改内容")
          .doesNotContain("<figure", "data-file-id");
      assertThat(word.getAllPictures()).hasSize(1);
    }
  }

  @Test
  void keepsImageAspectRatioInGeneratedWord() throws Exception {
    var generator = new ReportDocumentGenerator("");
    String imageId = "wide-image";
    var sections =
        new ReportSections(
            "图片比例报告",
            "情况正文",
            "<figure data-file-id=\""
                + imageId
                + "\"><img data-file-id=\""
                + imageId
                + "\" src=\"/api/v1/files/wide-image?inline=true\"></figure>",
            "整改内容");
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", imageBytes);

    var generated =
        generator.generate(
            sections,
            List.of(new ReportImage(imageId, "wide.png", "image/png", imageBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      var picture = word.getParagraphs().stream()
          .flatMap(paragraph -> paragraph.getRuns().stream())
          .flatMap(run -> run.getEmbeddedPictures().stream())
          .findFirst()
          .orElseThrow();
      var extent = picture.getCTPicture().getSpPr().getXfrm().getExt();
      double ratio = (double) extent.getCx() / (double) extent.getCy();
      assertThat(ratio).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.02));
    }
  }

  @Test
  void usesSavedDisplaySizeWhenGeneratingWordImages() throws Exception {
    var generator = new ReportDocumentGenerator("");
    String imageId = "display-sized-image";
    var sections =
        new ReportSections(
            "图片显示尺寸报告",
            "情况正文",
            "<figure data-file-id=\""
                + imageId
                + "\" data-display-width=\"320\" data-display-height=\"160\">"
                + "<img data-file-id=\""
                + imageId
                + "\" width=\"320\" height=\"160\" style=\"width:320px;height:160px\""
                + " src=\"/api/v1/files/display-sized-image?inline=true\"></figure>",
            "整改内容");
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", imageBytes);

    var generated =
        generator.generate(
            sections,
            List.of(new ReportImage(imageId, "wide.png", "image/png", imageBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      var extent = firstPictureExtent(word);
      assertThat(extent.getCx()).isCloseTo(Units.toEMU(240), org.assertj.core.data.Offset.offset(8L));
      assertThat(extent.getCy()).isCloseTo(Units.toEMU(120), org.assertj.core.data.Offset.offset(8L));
    }
  }

  @Test
  void correctsMismatchedSavedDisplayHeightWhenGeneratingWordImages() throws Exception {
    var generator = new ReportDocumentGenerator("");
    String imageId = "mismatched-image";
    var sections =
        new ReportSections(
            "图片防变形报告",
            "情况正文",
            "<figure data-file-id=\""
                + imageId
                + "\" data-display-width=\"320\" data-display-height=\"999\">"
                + "<img data-file-id=\""
                + imageId
                + "\" width=\"320\" height=\"999\" style=\"width:320px;height:999px\""
                + " src=\"/api/v1/files/mismatched-image?inline=true\"></figure>",
            "整改内容");
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", imageBytes);

    var generated =
        generator.generate(
            sections,
            List.of(new ReportImage(imageId, "wide.png", "image/png", imageBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      var extent = firstPictureExtent(word);
      assertThat(extent.getCx()).isCloseTo(Units.toEMU(240), org.assertj.core.data.Offset.offset(8L));
      assertThat(extent.getCy()).isCloseTo(Units.toEMU(120), org.assertj.core.data.Offset.offset(8L));
    }
  }

  @Test
  void keepsWordImageDisplaySizeInHistoricalPreviewHtml() throws Exception {
    var generator = new ReportDocumentGenerator("");
    byte[] imageBytes;
    try (var imageOutput = new ByteArrayOutputStream()) {
      ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", imageOutput);
      imageBytes = imageOutput.toByteArray();
    }
    byte[] wordBytes;
    try (var document = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      var paragraph = document.createParagraph();
      paragraph
          .createRun()
          .addPicture(
              new ByteArrayInputStream(imageBytes),
              XWPFDocument.PICTURE_TYPE_PNG,
              "现场图片.png",
              Units.toEMU(144),
              Units.toEMU(72));
      document.write(output);
      wordBytes = output.toByteArray();
    }

    String html = generator.extractWordPreviewHtml(wordBytes, "历史报告.docx");

    assertThat(html)
        .contains("<span class=\"word-inline-image\"><img")
        .contains("data-display-width=\"192\"")
        .contains("data-display-height=\"96\"")
        .contains("style=\"width:192px;height:96px\"");
  }

  @Test
  void marksImageOnlyHistoricalPreviewParagraphsForInlineFlow() throws Exception {
    var generator = new ReportDocumentGenerator("");
    byte[] imageBytes;
    try (var imageOutput = new ByteArrayOutputStream()) {
      ImageIO.write(new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "png", imageOutput);
      imageBytes = imageOutput.toByteArray();
    }
    byte[] wordBytes;
    try (var document = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      for (String name : List.of("左图.png", "右图.png")) {
        var paragraph = document.createParagraph();
        paragraph
            .createRun()
            .addPicture(
                new ByteArrayInputStream(imageBytes),
                XWPFDocument.PICTURE_TYPE_PNG,
                name,
                Units.toEMU(90),
                Units.toEMU(45));
      }
      document.write(output);
      wordBytes = output.toByteArray();
    }

    String html = generator.extractWordPreviewHtml(wordBytes, "历史报告.docx");

    assertThat(countOccurrences(html, "class=\"word-image-paragraph\"")).isEqualTo(2);
    assertThat(html).contains("<p class=\"word-image-paragraph\"><span class=\"word-inline-image\"><img");
  }

  @Test
  void keepsTextAfterImageInSeparateHistoricalPreviewParagraph() throws Exception {
    var generator = new ReportDocumentGenerator("");
    byte[] imageBytes;
    try (var imageOutput = new ByteArrayOutputStream()) {
      ImageIO.write(new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "png", imageOutput);
      imageBytes = imageOutput.toByteArray();
    }
    byte[] wordBytes;
    try (var document = new XWPFDocument();
        var output = new ByteArrayOutputStream()) {
      document
          .createParagraph()
          .createRun()
          .addPicture(
              new ByteArrayInputStream(imageBytes),
              XWPFDocument.PICTURE_TYPE_PNG,
              "现场图.png",
              Units.toEMU(90),
              Units.toEMU(45));
      document.createParagraph().createRun().setText("text-after-image");
      document.write(output);
      wordBytes = output.toByteArray();
    }

    String html = generator.extractWordPreviewHtml(wordBytes, "历史报告.docx");

    assertThat(html)
        .contains("</span></p><p>text-after-image</p>");
  }

  @Test
  void emphasizesConcreteCauseInGeneratedWord() throws Exception {
    var generator = new ReportDocumentGenerator("");
    var sections =
        new ReportSections(
            "重点原因报告",
            "情况正文",
            "本期电量同比超标原因：分摊比例变化导致本期电量升高。设备情况：现场设备照片。",
            "整改小结");

    var generated = generator.generate(sections, List.of());

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      var boldText =
          word.getParagraphs().stream()
              .flatMap(paragraph -> paragraph.getRuns().stream())
              .filter(run -> Boolean.TRUE.equals(run.isBold()))
              .map(run -> run.text())
              .toList();
      assertThat(boldText).anyMatch(text -> text.contains("分摊比例变化导致本期电量升高。"));
      assertThat(boldText).noneMatch(text -> text.contains("本期电量同比超标原因"));
      assertThat(boldText).noneMatch(text -> text.contains("设备情况"));
    }
  }

  @Test
  void emphasizesTextAfterGenericCauseLabelInGeneratedWord() throws Exception {
    var generator = new ReportDocumentGenerator("");
    var sections =
        new ReportSections(
            "通用原因报告",
            "情况正文",
            "超标原因是资管系统未及时更新导致额定功率标杆偏低。",
            "整改小结");

    var generated = generator.generate(sections, List.of());

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      var boldText =
          word.getParagraphs().stream()
              .flatMap(paragraph -> paragraph.getRuns().stream())
              .filter(run -> Boolean.TRUE.equals(run.isBold()))
              .map(run -> run.text())
              .toList();
      assertThat(boldText).anyMatch(text -> text.contains("资管系统未及时更新导致额定功率标杆偏低。"));
      assertThat(boldText).noneMatch(text -> text.contains("超标原因是"));
    }
  }

  @Test
  void shrinksSavedDisplaySizeOnlyWhenItExceedsPageWidth() throws Exception {
    var generator = new ReportDocumentGenerator("");
    String imageId = "large-inline-image";
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1200, 400, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
    var sections =
        new ReportSections(
            "图片尺寸报告",
            "情况正文",
            "<figure data-file-id=\""
                + imageId
                + "\" data-display-width=\"900\" data-display-height=\"300\"><img data-file-id=\""
                + imageId
                + "\" src=\"/api/v1/files/large-inline-image?inline=true\"></figure>",
            "整改内容");

    var generated =
        generator.generate(
            sections,
            List.of(
                new ReportImage(imageId, "large.png", "image/png", imageBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      var picture = word.getParagraphs().stream()
          .flatMap(paragraph -> paragraph.getRuns().stream())
          .flatMap(run -> run.getEmbeddedPictures().stream())
          .findFirst()
          .orElseThrow();
      var extent = picture.getCTPicture().getSpPr().getXfrm().getExt();
      assertThat(extent.getCx()).isEqualTo(Units.toEMU(420));
      assertThat(extent.getCy()).isEqualTo(Units.toEMU(140));
    }
  }

  @Test
  void doesNotEnlargeNaturalImageWhenGeneratedWordImageHasNoSavedDisplaySize() throws Exception {
    var generator = new ReportDocumentGenerator("");
    String imageId = "small-natural-image";
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
    var sections =
        new ReportSections(
            "自然尺寸报告",
            "情况正文",
            "<figure data-file-id=\""
                + imageId
                + "\"><img data-file-id=\""
                + imageId
                + "\" src=\"/api/v1/files/small-natural-image?inline=true\"></figure>",
            "整改内容");

    var generated =
        generator.generate(
            sections,
            List.of(
                new ReportImage(imageId, "small.png", "image/png", imageBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      var extent = firstPictureExtent(word);
      assertThat(extent.getCx()).isEqualTo(Units.toEMU(90));
      assertThat(extent.getCy()).isEqualTo(Units.toEMU(60));
    }
  }

  @Test
  void doesNotEmphasizeMetricComparisonOnlyText() throws Exception {
    var generator = new ReportDocumentGenerator("");
    var sections =
        new ReportSections(
            "数据不加粗报告",
            "情况正文",
            "<p><strong>本期日均用电量43.87度，同比正常上限42.05度，超标4.33%</strong></p>",
            "经核查，不存在用电量跑冒滴漏现象，不存在偷搭电问题，实际用电情况正常。");

    var generated = generator.generate(sections, List.of());

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      var boldText =
          word.getParagraphs().stream()
              .flatMap(paragraph -> paragraph.getRuns().stream())
              .filter(run -> Boolean.TRUE.equals(run.isBold()))
              .map(run -> run.text())
              .toList();
      assertThat(boldText).noneMatch(text -> text.contains("本期日均用电量"));
      assertThat(boldText).anyMatch(text -> text.contains("不存在用电量跑冒滴漏现象，不存在偷搭电问题"));
      assertThat(boldText).anyMatch(text -> text.contains("实际用电情况正常"));
    }
  }

  @Test
  void generatesWordWhenInlineImageSpacerBrHasAttributes() throws Exception {
    var generator = new ReportDocumentGenerator("");
    String imageId = "inline-image-1";
    var sections =
        new ReportSections(
            "内嵌图片报告",
            "情况正文",
            "<div>设备情况：</div><figure data-file-id=\""
                + imageId
                + "\"><img data-file-id=\""
                + imageId
                + "\" src=\"/api/v1/files/inline-image-1?inline=true\"></figure>"
                + "<br data-inline-image-spacer=\"true\">"
                + "<div>本期电量同比超标原因：分摊比例变化。</div>",
            "整改内容");
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", imageBytes);

    var generated =
        generator.generate(
            sections,
            List.of(
                new ReportImage(imageId, "evidence.png", "image/png", imageBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()));
        var extractor = new XWPFWordExtractor(word)) {
      assertThat(extractor.getText()).contains("设备情况", "本期电量同比超标原因", "整改内容");
      assertThat(word.getAllPictures()).hasSize(1);
    }
  }

  @Test
  void ignoresDraftEditorChromeWhenGeneratingWord() throws Exception {
    var generator = new ReportDocumentGenerator("");
    String imageId = "inline-image-1";
    var sections =
        new ReportSections(
            "编辑器临时元素报告",
            "情况正文",
            "<div>图片前文字</div>"
                + "<span class=\"inline-image-drop-marker\">不应导出</span>"
                + "<figure class=\"inline-report-image is-selected\" data-file-id=\""
                + imageId
                + "\" data-display-width=\"120\" data-display-height=\"80\">"
                + "<img data-file-id=\""
                + imageId
                + "\" width=\"120\" height=\"80\" style=\"width:120px;height:80px\" />"
                + "<span class=\"inline-image-resize-handle se\">不应导出</span>"
                + "</figure>"
                + "<span class=\"inline-image-uploading\">图片上传中...</span>"
                + "<div>图片后文字</div>",
            "整改内容");
    var imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png", imageBytes);

    var generated =
        generator.generate(
            sections,
            List.of(
                new ReportImage(imageId, "evidence.png", "image/png", imageBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()));
        var extractor = new XWPFWordExtractor(word)) {
      assertThat(extractor.getText())
          .contains("图片前文字", "图片后文字")
          .doesNotContain("不应导出", "图片上传中");
      assertThat(word.getAllPictures()).hasSize(1);
      var extent = firstPictureExtent(word);
      assertThat(extent.getCx()).isCloseTo(Units.toEMU(90), org.assertj.core.data.Offset.offset(8L));
      assertThat(extent.getCy()).isCloseTo(Units.toEMU(60), org.assertj.core.data.Offset.offset(8L));
    }
  }

  @Test
  void keepsInlineImageRowOnOneWordLineWhenImagesFitA4Width() throws Exception {
    var generator = new ReportDocumentGenerator("");
    var firstBytes = new ByteArrayOutputStream();
    var secondBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", firstBytes);
    ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", secondBytes);
    var sections =
        new ReportSections(
            "并排图片报告",
            "情况正文",
            "<div class=\"inline-image-row\" data-image-group-id=\"group-1\">"
                + "<figure class=\"inline-report-image\" data-file-id=\"row-image-1\""
                + " data-display-width=\"220\" data-display-height=\"110\">"
                + "<img data-file-id=\"row-image-1\" width=\"220\" height=\"110\" /></figure>"
                + "<figure class=\"inline-report-image\" data-file-id=\"row-image-2\""
                + " data-display-width=\"220\" data-display-height=\"110\">"
                + "<img data-file-id=\"row-image-2\" width=\"220\" height=\"110\" /></figure>"
                + "</div>",
            "整改内容");

    var generated =
        generator.generate(
            sections,
            List.of(
                new ReportImage("row-image-1", "first.png", "image/png", firstBytes.toByteArray()),
                new ReportImage("row-image-2", "second.png", "image/png", secondBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      assertThat(word.getTables()).hasSize(1);
      var table = word.getTables().getFirst();
      assertThat(table.getRows()).hasSize(1);
      assertThat(table.getRow(0).getTableCells()).hasSize(2);
      long embeddedPictureCount =
          table.getRow(0).getTableCells().stream()
              .flatMap(cell -> cell.getParagraphs().stream())
              .flatMap(paragraph -> paragraph.getRuns().stream())
              .flatMap(run -> run.getEmbeddedPictures().stream())
              .count();
      assertThat(embeddedPictureCount).isEqualTo(2);
      var extents =
          table.getRow(0).getTableCells().stream()
              .flatMap(cell -> cell.getParagraphs().stream())
              .flatMap(paragraph -> paragraph.getRuns().stream())
              .flatMap(run -> run.getEmbeddedPictures().stream())
              .map(picture -> picture.getCTPicture().getSpPr().getXfrm().getExt())
              .toList();
      assertThat(extents).hasSize(2);
      long totalWidth = extents.stream().mapToLong(extent -> extent.getCx()).sum();
      assertThat(totalWidth).isLessThanOrEqualTo(Units.toEMU(420));
    }
  }

  @Test
  void wrapsInlineImageRowInWordWhenImagesDoNotFitA4Width() throws Exception {
    var generator = new ReportDocumentGenerator("");
    var firstBytes = new ByteArrayOutputStream();
    var secondBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", firstBytes);
    ImageIO.write(new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "png", secondBytes);
    var sections =
        new ReportSections(
            "自然换行图片报告",
            "情况正文",
            "<div class=\"inline-image-row\" data-image-group-id=\"group-1\">"
                + "<figure class=\"inline-report-image\" data-file-id=\"row-image-1\""
                + " data-display-width=\"360\" data-display-height=\"180\">"
                + "<img data-file-id=\"row-image-1\" width=\"360\" height=\"180\" /></figure>"
                + "<figure class=\"inline-report-image\" data-file-id=\"row-image-2\""
                + " data-display-width=\"360\" data-display-height=\"180\">"
                + "<img data-file-id=\"row-image-2\" width=\"360\" height=\"180\" /></figure>"
                + "</div>",
            "整改内容");

    var generated =
        generator.generate(
            sections,
            List.of(
                new ReportImage("row-image-1", "first.png", "image/png", firstBytes.toByteArray()),
                new ReportImage("row-image-2", "second.png", "image/png", secondBytes.toByteArray())));

    try (var word = new XWPFDocument(new ByteArrayInputStream(generated.word()))) {
      assertThat(word.getTables()).hasSize(2);
      assertThat(word.getTables())
          .allSatisfy(
              table -> {
                assertThat(table.getRows()).hasSize(1);
                assertThat(table.getRow(0).getTableCells()).hasSize(1);
              });
      long embeddedPictureCount =
          word.getTables().stream()
              .flatMap(table -> table.getRows().stream())
              .flatMap(row -> row.getTableCells().stream())
              .flatMap(cell -> cell.getParagraphs().stream())
              .flatMap(paragraph -> paragraph.getRuns().stream())
              .flatMap(run -> run.getEmbeddedPictures().stream())
              .count();
      assertThat(embeddedPictureCount).isEqualTo(2);
    }
  }

  private int countOccurrences(String value, String needle) {
    int count = 0;
    int index = 0;
    while ((index = value.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }

  private org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D firstPictureExtent(
      XWPFDocument word) {
    return word.getParagraphs().stream()
        .flatMap(paragraph -> paragraph.getRuns().stream())
        .flatMap(run -> run.getEmbeddedPictures().stream())
        .findFirst()
        .orElseThrow()
        .getCTPicture()
        .getSpPr()
        .getXfrm()
        .getExt();
  }

  @Test
  void cleansWordControlCharactersBeforeHistoricalPdfPreview() throws Exception {
    var generator = new ReportDocumentGenerator("");
    var sections = new ReportSections("历史报告", "情况\u0001正文", "排查正文", "整改正文");

    var text =
        generator.extractWordText(generator.generate(sections, List.of()).word(), "历史报告.docx");
    var pdf = generator.generateHistoricalPdf("历史报告", text);

    try (var document = Loader.loadPDF(pdf)) {
      assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  void removesLegacyDocPictureFieldCodesFromPreviewText() throws Exception {
    var generator = new ReportDocumentGenerator("");
    Method method = ReportDocumentGenerator.class.getDeclaredMethod("cleanWordText", String.class);
    method.setAccessible(true);

    String cleaned =
        (String)
            method.invoke(
                generator,
                """
                移动：华为4GBBU*1+RRU*3
                INCLUDEPICTURE \\d "http://180.153.49.130:9000/imageMountShow?imageId=/itower18/2020/07/21/13/xunjian/demo.jpg" \\* MERGEFORMATINET
                三、整改小结
                """);

    assertThat(cleaned)
        .contains("移动：华为4GBBU*1+RRU*3", "三、整改小结")
        .doesNotContain("INCLUDEPICTURE", "MERGEFORMAT", "imageMountShow", "http://");
  }
}
