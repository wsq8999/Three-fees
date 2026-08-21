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

  private int countOccurrences(String value, String needle) {
    int count = 0;
    int index = 0;
    while ((index = value.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
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
