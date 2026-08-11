package com.threefees.report.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ai.application.AiServiceClient.ReportSections;
import java.io.ByteArrayInputStream;
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
}
