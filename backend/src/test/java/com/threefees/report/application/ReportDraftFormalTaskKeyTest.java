package com.threefees.report.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ai.application.AiServiceClient.ReportSections;
import org.junit.jupiter.api.Test;

class ReportDraftFormalTaskKeyTest {

  @Test
  void bindsFormalReportTaskToTheExactConfirmedContentVersion() {
    String first = ReportDraftService.formalTaskBusinessKey("draft-1", 3);
    String duplicate = ReportDraftService.formalTaskBusinessKey("draft-1", 3);
    String corrected = ReportDraftService.formalTaskBusinessKey("draft-1", 4);

    assertThat(duplicate).isEqualTo(first);
    assertThat(corrected).isNotEqualTo(first);
  }

  @Test
  void treatsOnlySingleSectionHistoricalHtmlAsACompleteDocument() {
    var systemReport = new ReportSections("最终确认报告", "<p>情况说明</p>", "<p>排查分析</p>", "<p>整改小结</p>");
    var importedReport = new ReportSections("历史报告", "<article>完整原始报告</article>", "", "");

    assertThat(FormalReportTaskProcessor.isFullDocumentHtml(systemReport)).isFalse();
    assertThat(FormalReportTaskProcessor.isFullDocumentHtml(importedReport)).isTrue();
  }
}
