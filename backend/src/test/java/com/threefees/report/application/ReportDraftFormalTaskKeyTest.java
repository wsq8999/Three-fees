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
    assertThat(FormalReportTaskProcessor.isHtmlDraft(systemReport)).isTrue();
    assertThat(FormalReportTaskProcessor.isHtmlDraft(importedReport)).isTrue();
  }

  @Test
  void buildsCompleteHtmlForRegularTipTapDraftBeforeWordExport() {
    var sections =
        new ReportSections(
            "最终确认报告",
            "<p>情况说明</p>",
            "<div class=\"inline-image-row\" data-image-group-id=\"group-1\">"
                + "<figure class=\"inline-report-image\" data-file-id=\"file-1\"><img data-file-id=\"file-1\" /></figure>"
                + "<figure class=\"inline-report-image\" data-file-id=\"file-2\"><img data-file-id=\"file-2\" /></figure>"
                + "</div><p>排查分析</p>",
            "<p>整改小结</p>");

    String html = FormalReportTaskProcessor.reportHtml(sections);

    assertThat(html)
        .startsWith("<article class=\"confirmed-report-content\"><h1>最终确认报告</h1>")
        .containsSubsequence("一、情况说明", "情况说明", "二、排查分析", "inline-image-row", "file-1", "file-2", "三、整改小结", "整改小结");
  }
}
