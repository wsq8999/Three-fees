package com.threefees.report.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ai.application.AiServiceClient.ReportSections;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
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

    var text = generator.extractWordText(generator.generate(sections, List.of()).word(), "历史报告.docx");

    assertThat(text).contains("历史报告", "情况正文", "排查正文", "整改正文");
  }

  @Test
  void cleansWordControlCharactersBeforeHistoricalPdfPreview() throws Exception {
    var generator = new ReportDocumentGenerator("");
    var sections = new ReportSections("历史报告", "情况\u0001正文", "排查正文", "整改正文");

    var text = generator.extractWordText(generator.generate(sections, List.of()).word(), "历史报告.docx");
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
