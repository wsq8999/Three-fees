package com.threefees.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.threefees.ai.application.AiServiceClient.AgentContext;
import com.threefees.ai.application.AiServiceClient.ReportSections;
import com.threefees.ai.application.CityMemoryService;
import com.threefees.ai.application.CityMemoryService.MemoryQuery;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import com.threefees.task.api.TaskController;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

@SpringBootTest(properties = {"three-fees.process-role=api", "app.bootstrap.enabled=false"})
class ReportDraftCityMemoryIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ReportDraftService service;
  @Autowired private CityMemoryService cityMemoryService;
  @Autowired private TaskController taskController;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM ai_city_memory WHERE confirmed_by='city-memory-test'");
    jdbcTemplate.update(
        "DELETE FROM ai_billing_point_memory_profile WHERE city_code IN ('320100', '320500')");
    jdbcTemplate.update(
        """
        DELETE FROM historical_audit_case
         WHERE report_id IN (SELECT id FROM audit_report WHERE updated_by='city-memory-test')
        """);
    jdbcTemplate.update("DELETE FROM audit_report WHERE updated_by='city-memory-test'");
    jdbcTemplate.update(
        "DELETE FROM report_draft_version WHERE draft_id IN (SELECT id FROM report_draft WHERE created_by='city-memory-test')");
    jdbcTemplate.update("DELETE FROM business_task WHERE created_by='city-memory-test'");
    jdbcTemplate.update("DELETE FROM report_draft WHERE created_by='city-memory-test'");
    jdbcTemplate.update(
        """
        DELETE FROM audit_result
         WHERE billing_point_code IN ('MEMORY-POINT-001', 'CITY-OTHER-001', 'SUZHOU-OTHER-001')
        """);
    jdbcTemplate.update(
        """
        DELETE FROM billing_point_snapshot
         WHERE billing_point_code IN ('MEMORY-POINT-001', 'CITY-OTHER-001', 'SUZHOU-OTHER-001')
        """);
    jdbcTemplate.update(
        """
        DELETE FROM billing_point_master
         WHERE billing_point_code IN ('MEMORY-POINT-001', 'CITY-OTHER-001', 'SUZHOU-OTHER-001')
        """);
    jdbcTemplate.update("DELETE FROM import_job WHERE created_by='city-memory-test'");
    jdbcTemplate.update("DELETE FROM stored_file WHERE created_by='city-memory-test'");
  }

  @Test
  void cityMemoryQueryNeverReturnsAnotherCityMemory() {
    insertMemory("320100", "南京空调运行变化");
    insertMemory("320500", "苏州设备扩容");

    var nanjing = service.cityMemoryReferences("320100");

    assertThat(nanjing).hasSize(1);
    assertThat(nanjing.getFirst().summary()).contains("南京空调运行变化");
    assertThat(nanjing.getFirst().summary()).doesNotContain("苏州设备扩容");
  }

  @Test
  void createsARecoverableInitialDraftAndVersionWithoutFastApi() {
    String snapshotId = seedOverLimitSnapshot();
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));

    var draft = service.createOrResume(snapshotId, actor);

    assertThat(draft.cityCode()).isEqualTo("320100");
    assertThat(draft.sections().title()).contains("电费稽核说明");
    assertThat(draft.sections().analysis()).isEmpty();
    assertThat(draft.sections().rectification()).isEmpty();
    assertThat(draft.currentVersion()).isZero();
    assertThat(service.versions(draft.publicId(), actor)).hasSize(1);
  }

  @Test
  void agentContextSeparatesSameCityAndExternalProvinceReferences() throws Exception {
    String currentSnapshotId = seedOverLimitSnapshot();
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(currentSnapshotId, actor);

    seedAuditReport(
        "320100",
        "MEMORY-POINT-001",
        "南京测试报账点",
        "2026-06",
        "南京同点历史：上月核查为空调运行时长增加。");
    seedAuditReport(
        "320100",
        "CITY-OTHER-001",
        "南京其他报账点",
        "2026-07",
        "南京本市其他案例：设备负载变化导致环比升高。");
    seedAuditReport(
        "320500",
        "SUZHOU-OTHER-001",
        "苏州其他报账点",
        "2026-07",
        "苏州外市案例：设备扩容后用电增长。");

    AgentContext context = agentContext(draft, "IMAGE_ANALYSIS");

    assertThat(context.samePointCases())
        .anySatisfy(
            reference ->
                assertThat(reference.summary())
                    .contains("南京同点历史", "历史明确原因", "历史原因类型"));
    assertThat(context.cityMemories())
        .anySatisfy(reference -> assertThat(reference.summary()).contains("南京本市其他案例"));
    assertThat(context.cityMemories())
        .allMatch(reference -> reference.cityCode() == null || reference.cityCode().equals("320100"));
    assertThat(context.provinceReferences())
        .anySatisfy(
            reference -> {
              assertThat(reference.id()).startsWith("OUTCITY-CASE-");
              assertThat(reference.cityCode()).isEqualTo("320500");
              assertThat(reference.summary()).contains("外市参考", "城市=320500", "苏州外市案例");
            });
    assertThat(context.provinceReferences()).noneMatch(reference -> "320100".equals(reference.cityCode()));
  }

  @Test
  void latestUserCorrectionBecomesTheOnlyActiveCityMemoryForTheDraft() {
    String snapshotId = seedOverLimitSnapshot();
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(snapshotId, actor);

    long firstMessageId = insertCorrectionMessage(draft.id(), "实际原因是空调集中运行");
    cityMemoryService.rememberUserCorrection(
        draft.id(),
        firstMessageId,
        "实际原因是空调集中运行",
        "原因待核实",
        "空调集中运行",
        "现场记录确认空调集中运行。",
        "调整空调运行时段。",
        "city-memory-test");
    long secondMessageId = insertCorrectionMessage(draft.id(), "实际原因是新增设备投运");
    cityMemoryService.rememberUserCorrection(
        draft.id(),
        secondMessageId,
        "实际原因是新增设备投运",
        "空调集中运行",
        "新增设备投运",
        "设备台账确认本月新增设备。",
        "补充设备变更备案。",
        "city-memory-test");

    Integer activeCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ai_city_memory WHERE confirmed_by='city-memory-test' AND active=TRUE",
            Integer.class);
    String finalReason =
        jdbcTemplate.queryForObject(
            "SELECT final_reason FROM ai_city_memory WHERE confirmed_by='city-memory-test' AND active=TRUE",
            String.class);
    assertThat(activeCount).isEqualTo(1);
    assertThat(finalReason).isEqualTo("新增设备投运");
    assertThat(service.cityMemoryReferences("320500")).isEmpty();
  }

  @Test
  void duplicateCorrectionsAreMergedAndPointProfileDrivesRelevantRetrieval() {
    String snapshotId = seedOverLimitSnapshot();
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(snapshotId, actor);

    long firstMessageId = insertCorrectionMessage(draft.id(), "实际原因是夏季空调集中运行");
    cityMemoryService.rememberUserCorrection(
        draft.id(),
        firstMessageId,
        "实际原因是夏季空调集中运行",
        "原因待核实",
        "夏季空调集中运行",
        "现场记录和图片确认空调负荷增加。",
        "调整空调运行时段。",
        "city-memory-test");
    long secondMessageId = insertCorrectionMessage(draft.id(), "确认仍是夏季空调集中运行");
    cityMemoryService.rememberUserCorrection(
        draft.id(),
        secondMessageId,
        "确认仍是夏季空调集中运行",
        "空调负荷增加",
        "夏季空调集中运行",
        "再次核验空调负荷记录。",
        "继续执行空调错峰方案。",
        "city-memory-test");
    insertMemory("320100", "本市其他报账点计量异常");
    insertMemory("320500", "苏州工业峰谷时段异常");

    Integer memoryCount =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM ai_city_memory
             WHERE city_code='320100' AND billing_point_code='MEMORY-POINT-001'
               AND final_reason='夏季空调集中运行'
            """,
            Integer.class);
    Integer confirmCount =
        jdbcTemplate.queryForObject(
            """
            SELECT confirm_count FROM ai_city_memory
             WHERE city_code='320100' AND billing_point_code='MEMORY-POINT-001'
               AND final_reason='夏季空调集中运行'
            """,
            Integer.class);
    String memoryFeatures =
        jdbcTemplate.queryForObject(
            """
            SELECT CONCAT(reason_code, '|', season_code, '|', ratio_bucket, '|', status)
              FROM ai_city_memory
             WHERE city_code='320100' AND billing_point_code='MEMORY-POINT-001'
               AND final_reason='夏季空调集中运行'
            """,
            String.class);
    var profile = cityMemoryService.findPointProfile("320100", "MEMORY-POINT-001");
    var matches =
        cityMemoryService.findRelevantMemories(
            new MemoryQuery(
                "320100",
                "MEMORY-POINT-001",
                "ONLY_MOM",
                "2026-07",
                new java.math.BigDecimal("18.5")),
            5);

    assertThat(memoryCount).isEqualTo(1);
    assertThat(confirmCount).isEqualTo(2);
    assertThat(memoryFeatures).isEqualTo("AIR_CONDITIONING|SUMMER|10_TO_20|ACTIVE");
    assertThat(profile).isNotNull();
    assertThat(profile.activeMemoryCount()).isEqualTo(1);
    assertThat(profile.summary()).contains("夏季空调集中运行", "累计确认2次");
    assertThat(matches).isNotEmpty();
    assertThat(matches.getFirst().billingPointCode()).isEqualTo("MEMORY-POINT-001");
    assertThat(matches).allMatch(memory -> memory.cityCode().equals("320100"));
  }

  @Test
  void reorderAndRemovalKeepImageOrderAndAnalysisNumbersConsistent() {
    String snapshotId = seedOverLimitSnapshot();
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(snapshotId, actor);
    List<String> imageIds = seedDraftImages(draft.id());
    draft = service.find(draft.publicId(), actor);
    draft =
        service.edit(
            draft.publicId(),
            new ReportSections(
                draft.sections().title(),
                draft.sections().situation(),
                draft.sections().analysis()
                    + "<figure data-file-id=\""
                    + imageIds.get(0)
                    + "\"><img data-file-id=\""
                    + imageIds.get(0)
                    + "\"></figure>"
                    + "<figure data-file-id=\""
                    + imageIds.get(1)
                    + "\"><img data-file-id=\""
                    + imageIds.get(1)
                    + "\"></figure>",
                draft.sections().rectification()),
            draft.entityVersion(),
            actor);

    var reordered =
        service.reorderImages(
            draft.publicId(),
            List.of(imageIds.get(1), imageIds.get(0)),
            draft.entityVersion(),
            actor);

    assertThat(reordered.currentImageFileIds()).containsExactly(imageIds.get(1), imageIds.get(0));
    assertThat(imageSortNumbers(draft.id())).containsExactly(0, 1);
    assertThat(imageAnalysisJson(draft.id()).get(0)).contains("IMG-1");
    assertThat(imageAnalysisJson(draft.id()).get(1)).contains("IMG-2");

    var remaining =
        service.removeImage(draft.publicId(), imageIds.get(1), reordered.entityVersion(), actor);

    assertThat(remaining.currentImageFileIds()).containsExactly(imageIds.get(0));
    assertThat(remaining.sections().analysis())
        .contains(imageIds.get(0))
        .doesNotContain(imageIds.get(1));
    assertThat(imageSortNumbers(draft.id())).containsExactly(0);
    assertThat(imageAnalysisJson(draft.id()).getFirst()).contains("IMG-1").doesNotContain("IMG-2");
  }

  @Test
  void findSyncsFailedImageAnalysisTaskBackToDraft() {
    String snapshotId = seedOverLimitSnapshot();
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(snapshotId, actor);
    String taskId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO business_task
          (public_id, task_type, business_key, status, attempts, max_attempts,
           payload_json, error_code, created_by, updated_by)
        VALUES (?, 'AI_IMAGE_ANALYSIS', ?, 'FAILED', 1, 1, ?, 'KIMI_TIMEOUT',
                'city-memory-test', 'city-memory-test')
        """,
        taskId,
        "AI_IMAGE_ANALYSIS:SNAPSHOT:" + draft.billingPointPeriodId(),
        "{\"draftId\":\"" + draft.publicId() + "\",\"instruction\":\"测试\",\"imageFileIds\":[]}");
    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET analysis_status='AI_ANALYZING',
               analysis_task_public_id=?,
               analysis_error_code=NULL
         WHERE id=?
        """,
        taskId,
        draft.id());

    var loaded = service.find(draft.publicId(), actor);
    String storedStatus =
        jdbcTemplate.queryForObject(
            "SELECT analysis_status FROM report_draft WHERE id=?", String.class, draft.id());
    String storedError =
        jdbcTemplate.queryForObject(
            "SELECT analysis_error_code FROM report_draft WHERE id=?", String.class, draft.id());

    assertThat(loaded.analysisStatus()).isEqualTo("AI_FAILED");
    assertThat(loaded.analysisErrorCode()).isEqualTo("KIMI_TIMEOUT");
    assertThat(storedStatus).isEqualTo("AI_FAILED");
    assertThat(storedError).isEqualTo("KIMI_TIMEOUT");
  }

  @Test
  void discardsUnusedCorrectionDraftWithoutChangingReport() {
    String reportId =
        seedAuditReport(
            "320100",
            "MEMORY-POINT-001",
            "南京测试报账点",
            "2026-07",
            "历史明确原因。");
    CurrentUser actor = cityUser();
    var draft = service.createCorrection(reportId, "准备更正文案", actor);

    boolean discarded = service.discardUnusedCorrection(draft.publicId(), actor);

    assertThat(discarded).isTrue();
    Integer draftCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM report_draft WHERE public_id=?", Integer.class, draft.publicId());
    String reportStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM audit_report WHERE public_id=?", String.class, reportId);
    assertThat(draftCount).isZero();
    assertThat(reportStatus).isEqualTo("FORMALIZED");
  }

  @Test
  void keepsCorrectionDraftWhenContentChangedBeforeReturn() {
    String reportId =
        seedAuditReport(
            "320100",
            "MEMORY-POINT-001",
            "南京测试报账点",
            "2026-07",
            "历史明确原因。");
    CurrentUser actor = cityUser();
    var draft = service.createCorrection(reportId, "准备更正文案", actor);

    var edited =
        service.edit(
            draft.publicId(),
            new ReportSections("更正标题", "情况已修改", "分析已修改", "整改已修改"),
            draft.entityVersion(),
            actor);

    boolean discarded = service.discardUnusedCorrection(edited.publicId(), actor);

    assertThat(discarded).isFalse();
    String draftStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM report_draft WHERE public_id=?", String.class, draft.publicId());
    String reportStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM audit_report WHERE public_id=?", String.class, reportId);
    assertThat(draftStatus).isEqualTo("CORRECTING");
    assertThat(reportStatus).isEqualTo("FORMALIZED");
  }

  @Test
  void aiTaskListHidesResetCorrectionDraftUntilAnalysisIsSubmitted() {
    String reportId =
        seedAuditReport(
            "320100",
            "MEMORY-POINT-001",
            "南京测试报账点",
            "2026-07",
            "历史明确原因。");
    CurrentUser actor = cityUser();
    var draft = service.createCorrection(reportId, "准备更正文案", actor);
    String taskId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO business_task
          (public_id, task_type, business_key, status, attempts, max_attempts,
           payload_json, error_code, created_by, updated_by)
        VALUES (?, 'AI_IMAGE_ANALYSIS', ?, 'FAILED', 1, 1, ?, 'KIMI_TIMEOUT',
                'city-memory-test', 'city-memory-test')
        """,
        taskId,
        "AI_IMAGE_ANALYSIS:SNAPSHOT:" + draft.billingPointPeriodId(),
        "{\"draftId\":\"" + draft.publicId() + "\",\"instruction\":\"测试\",\"imageFileIds\":[]}");

    var hidden = taskController.list(null, null, null, null, 0, 20, actor);

    assertThat(hidden.items()).isEmpty();

    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET analysis_status='AI_FAILED',
               analysis_submitted_at=CURRENT_TIMESTAMP(3),
               analysis_error_code='KIMI_TIMEOUT'
         WHERE id=?
        """,
        draft.id());

    var visible = taskController.list(null, null, null, null, 0, 20, actor);

    assertThat(visible.items()).hasSize(1);
    assertThat(visible.items().getFirst().relatedDraftId()).isEqualTo(draft.publicId());
  }

  @Test
  void removesNonEquipmentImageLabelsButKeepsFigures() throws Exception {
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method = ReportDraftService.class.getDeclaredMethod("removeNonEquipmentImageLabels", String.class);
    method.setAccessible(true);
    String figure = "<figure data-file-id=\"file-1\"><img data-file-id=\"file-1\"></figure>";

    String cleaned =
        (String)
            method.invoke(
                target,
                "<p>缴费信息界面图：</p>"
                    + figure
                    + "<p>设备情况：移动4G BBU*1、RRU*3。</p>"
                    + "<figure data-file-id=\"file-2\"><img data-file-id=\"file-2\"></figure>");

    assertThat(cleaned)
        .doesNotContain("缴费信息界面图")
        .contains(figure)
        .contains("设备情况：移动4G BBU*1、RRU*3。")
        .contains("file-2");
  }

  @Test
  void movesEquipmentImageLabelsAboveTheirFigures() throws Exception {
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method =
        ReportDraftService.class.getDeclaredMethod("normalizeImageCaptionPositions", String.class);
    method.setAccessible(true);
    String firstFigure = "<figure data-file-id=\"file-1\"><img data-file-id=\"file-1\"></figure>";
    String secondFigure = "<figure data-file-id=\"file-2\"><img data-file-id=\"file-2\"></figure>";

    String cleaned =
        (String)
            method.invoke(
                target,
                firstFigure
                    + "<p>机房全景图：</p>"
                    + secondFigure
                    + "<p>设备情况：移动4G BBU*1、RRU*3。</p>");

    assertThat(cleaned)
        .containsSubsequence("<p>机房全景图：</p>", firstFigure)
        .containsSubsequence("<p>设备情况：移动4G BBU*1、RRU*3。</p>", secondFigure);
  }

  @Test
  void normalizesCommonKimiCopulaTypoWithoutChangingSystemWords() throws Exception {
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method = ReportDraftService.class.getDeclaredMethod("normalizeAiReportText", String.class);
    method.setAccessible(true);

    String cleaned =
        (String)
            method.invoke(
                target,
                "本期超标主要系分摊比例变化，原因系电信下电，系由于系统台账未更新，修正系数保持不变。");

    assertThat(cleaned)
        .contains("本期超标主要是分摊比例变化")
        .contains("原因是电信下电")
        .contains("是由于系统台账未更新")
        .contains("修正系数保持不变")
        .doesNotContain("主要系", "原因系", "系由于");
  }

  @Test
  void movesAnalysisReasonToEndAfterAllFigures() throws Exception {
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method = ReportDraftService.class.getDeclaredMethod("moveAnalysisReasonToEnd", String.class);
    method.setAccessible(true);
    String firstFigure = "<figure data-file-id=\"file-1\"><img data-file-id=\"file-1\"></figure>";
    String secondFigure = "<figure data-file-id=\"file-2\"><img data-file-id=\"file-2\"></figure>";

    String cleaned =
        (String)
            method.invoke(
                target,
                "<p>本期电量同比超标原因：分摊比例变化。</p>"
                    + "<p>机房全景图：</p>"
                    + firstFigure
                    + "<p>设备情况：移动4G BBU*1。</p>"
                    + secondFigure);

    assertThat(cleaned)
        .containsSubsequence("机房全景图", firstFigure, "设备情况", secondFigure, "本期电量同比超标原因");
  }

  @Test
  void ratedOverLimitFactsTellKimiToCheckAssetSystemLag() throws Exception {
    String snapshotId = seedOverLimitSnapshot();
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(snapshotId, actor);
    jdbcTemplate.update(
        """
        UPDATE audit_result
           SET rated_result='OVER_LIMIT',
               over_limit_type='ONLY_RATED',
               rated_ratio=27.26,
               rated_benchmark_energy=1033.54
         WHERE billing_point_code='MEMORY-POINT-001'
           AND data_period='2026-07'
           AND city_code='320100'
        """);
    draft = service.find(draft.publicId(), actor);
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method = ReportDraftService.class.getDeclaredMethod("facts", draft.getClass());
    method.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<com.threefees.ai.application.AiServiceClient.Fact> facts =
        (List<com.threefees.ai.application.AiServiceClient.Fact>) method.invoke(target, draft);

    assertThat(facts)
        .anySatisfy(
            fact -> {
              assertThat(fact.fieldName()).isEqualTo("额定超标重点排查方向");
              assertThat(fact.value()).contains("资管系统", "额定功率台账未及时更新", "不要默认写待核实");
            });
  }

  @Test
  void nonSummerPositionPointFactsTellKimiToUseMainPowerOnly() throws Exception {
    String snapshotId = seedOverLimitSnapshot();
    jdbcTemplate.update(
        """
        UPDATE billing_point_snapshot
           SET data_period='2026-01',
               period_start='2026-01-01',
               period_end='2026-01-31',
               data_json='{"siteType":"位置点"}'
         WHERE public_id=?
        """,
        snapshotId);
    jdbcTemplate.update(
        """
        UPDATE audit_result
           SET rated_result='OVER_LIMIT',
               over_limit_type='ONLY_RATED',
               rated_ratio=27.26,
               rated_benchmark_energy=1896.46,
               aggregated_payment_days=31
         WHERE billing_point_code='MEMORY-POINT-001'
           AND data_period='2026-07'
           AND city_code='320100'
        """);
    jdbcTemplate.update(
        """
        UPDATE audit_result
           SET data_period='2026-01',
               period_start='2026-01-01',
               period_end='2026-01-31'
         WHERE billing_point_code='MEMORY-POINT-001'
           AND data_period='2026-07'
           AND city_code='320100'
        """);
    seedPositionPointMaster("MEMORY-POINT-001", "南京测试报账点");
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(snapshotId, actor);
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method = ReportDraftService.class.getDeclaredMethod("facts", draft.getClass());
    method.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<com.threefees.ai.application.AiServiceClient.Fact> facts =
        (List<com.threefees.ai.application.AiServiceClient.Fact>) method.invoke(target, draft);

    assertThat(facts)
        .anySatisfy(
            fact -> {
              assertThat(fact.fieldName()).isEqualTo("位置点额定功率标杆公式");
              assertThat(fact.value())
                  .contains(
                      "非夏季1月-4月、11月-12月",
                      "主设备功率=2.549KW",
                      "空调总功率=2.12KW",
                      "本账期天数=31天",
                      "（2.549KW）*24小时*31天=1896.46度",
                      "公式仍不得自动加入空调功率",
                      "1896.46度");
            });
  }

  @Test
  void summerPositionPointFactsTellKimiToAddAirConditionerPower() throws Exception {
    String snapshotId = seedOverLimitSnapshot();
    jdbcTemplate.update(
        """
        UPDATE billing_point_snapshot
           SET data_json='{"siteType":"位置点"}'
         WHERE public_id=?
        """,
        snapshotId);
    jdbcTemplate.update(
        """
        UPDATE audit_result
           SET rated_result='OVER_LIMIT',
               over_limit_type='ONLY_RATED',
               rated_ratio=27.26,
               rated_benchmark_energy=3473.04,
               aggregated_payment_days=31
         WHERE billing_point_code='MEMORY-POINT-001'
           AND data_period='2026-07'
           AND city_code='320100'
        """);
    seedPositionPointMaster("MEMORY-POINT-001", "南京测试报账点");
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(snapshotId, actor);
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method = ReportDraftService.class.getDeclaredMethod("facts", draft.getClass());
    method.setAccessible(true);

    @SuppressWarnings("unchecked")
    List<com.threefees.ai.application.AiServiceClient.Fact> facts =
        (List<com.threefees.ai.application.AiServiceClient.Fact>) method.invoke(target, draft);

    assertThat(facts)
        .anySatisfy(
            fact -> {
              assertThat(fact.fieldName()).isEqualTo("位置点额定功率标杆公式");
              assertThat(fact.value())
                  .contains(
                      "夏季5月-10月",
                      "主设备功率=2.549KW",
                      "空调总功率=2.12KW",
                      "（2.549KW+2.12KW）*24小时*31天=3473.04度");
            });
  }

  @Test
  void nonSummerPositionPointFormulaRemovesAirConditionerPowerBeforeSave() throws Exception {
    String snapshotId = seedOverLimitSnapshot();
    jdbcTemplate.update(
        """
        UPDATE billing_point_snapshot
           SET data_period='2026-01',
               period_start='2026-01-01',
               period_end='2026-01-31',
               data_json='{"siteType":"位置点"}'
         WHERE public_id=?
        """,
        snapshotId);
    jdbcTemplate.update(
        """
        UPDATE audit_result
           SET data_period='2026-01',
               period_start='2026-01-01',
               period_end='2026-01-31',
               rated_result='OVER_LIMIT',
               over_limit_type='ONLY_RATED',
               rated_ratio=27.26,
               rated_benchmark_energy=1896.46,
               aggregated_payment_days=31
         WHERE billing_point_code='MEMORY-POINT-001'
           AND data_period='2026-07'
           AND city_code='320100'
        """);
    seedPositionPointMaster("MEMORY-POINT-001", "南京测试报账点");
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(snapshotId, actor);
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method =
        ReportDraftService.class.getDeclaredMethod(
            "normalizePositionRatedPowerFormula", draft.getClass(), ReportSections.class);
    method.setAccessible(true);

    ReportSections cleaned =
        (ReportSections)
            method.invoke(
                target,
                draft,
                new ReportSections(
                    "标题",
                    "情况说明",
                    "三费系统中对应额定功率标杆应为（0.363+2.12）KW*24小时*31天=1896.46度。",
                    "整改小结"));

    assertThat(cleaned.analysis())
        .contains("（2.549KW）*24小时*31天")
        .doesNotContain("0.363+2.12");
  }

  @Test
  void imageAnalysisRunCanBeAuditedWithoutVisibleChatMessage() throws Exception {
    String snapshotId = seedOverLimitSnapshot();
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    var draft = service.createOrResume(snapshotId, actor);
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method =
        ReportDraftService.class.getDeclaredMethod(
            "saveAnalysisRun", draft.getClass(), Long.class, int.class, AgentContext.class);
    method.setAccessible(true);

    method.invoke(target, draft, null, 2, AgentContext.empty());

    Integer messageCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM report_draft_message WHERE draft_id=?",
            Integer.class,
            draft.id());
    Integer runCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ai_analysis_run WHERE draft_id=? AND message_id IS NULL",
            Integer.class,
            draft.id());
    assertThat(messageCount).isZero();
    assertThat(runCount).isEqualTo(1);
  }

  private void insertMemory(String cityCode, String reason) {
    jdbcTemplate.update(
        """
        INSERT INTO ai_city_memory
          (public_id, city_code, final_reason, trust_level, confirmed_by)
        VALUES (?, ?, ?, 'CONFIRMED_REPORT', 'city-memory-test')
        """,
        UUID.randomUUID().toString(),
        cityCode,
        reason);
  }

  private CurrentUser cityUser() {
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    return actor;
  }

  private long insertCorrectionMessage(long draftId, String correction) {
    String publicId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO report_draft_message
          (public_id, draft_id, city_code, intent, user_content, assistant_content,
           changed_draft, image_file_ids_json, final_reason, created_by)
        VALUES (?, ?, '320100', 'CORRECTION', ?, '已按人工确认原因修改报告',
                TRUE, '[]', ?, 'city-memory-test')
        """,
        publicId,
        draftId,
        correction,
        correction);
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM report_draft_message WHERE public_id=?", Long.class, publicId);
    if (id == null) throw new IllegalStateException("Correction message was not inserted");
    return id;
  }

  private List<String> seedDraftImages(long draftId) {
    List<String> publicIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    for (int index = 0; index < publicIds.size(); index++) {
      String publicId = publicIds.get(index);
      jdbcTemplate.update(
          """
          INSERT INTO stored_file
            (public_id, storage_name, original_name, media_type, byte_size, sha256,
             purpose, created_by)
          VALUES (?, ?, ?, 'image/png', 1, ?, 'DRAFT_IMAGE', 'city-memory-test')
          """,
          publicId,
          publicId + ".png",
          "image-" + (index + 1) + ".png",
          Integer.toString(index).repeat(64));
      Long fileId =
          jdbcTemplate.queryForObject(
              "SELECT id FROM stored_file WHERE public_id=?", Long.class, publicId);
      jdbcTemplate.update(
          """
          INSERT INTO report_draft_image
            (public_id, draft_id, file_id, sort_no, analysis_json, created_by)
          VALUES (?, ?, ?, ?, ?, 'city-memory-test')
          """,
          UUID.randomUUID().toString(),
          draftId,
          fileId,
          index,
          "{\"imageId\":\"IMG-"
              + (index + 1)
              + "\",\"category\":\"测试图片\",\"observation\":\"观察\","
              + "\"evidence\":\"证据\",\"limitation\":\"限制\"}");
    }
    jdbcTemplate.update(
        "UPDATE report_draft SET current_image_file_ids_json=? WHERE id=?",
        "[\"" + String.join("\",\"", publicIds) + "\"]",
        draftId);
    return publicIds;
  }

  private List<Integer> imageSortNumbers(long draftId) {
    return jdbcTemplate.query(
        "SELECT sort_no FROM report_draft_image WHERE draft_id=? ORDER BY sort_no, id",
        (rs, row) -> rs.getInt("sort_no"),
        draftId);
  }

  private List<String> imageAnalysisJson(long draftId) {
    return jdbcTemplate.query(
        "SELECT analysis_json FROM report_draft_image WHERE draft_id=? ORDER BY sort_no, id",
        (rs, row) -> rs.getString("analysis_json"),
        draftId);
  }

  private AgentContext agentContext(Object draft, String intent) throws Exception {
    ReportDraftService target = AopTestUtils.getTargetObject(service);
    var method = ReportDraftService.class.getDeclaredMethod("agentContext", draft.getClass(), String.class);
    method.setAccessible(true);
    return (AgentContext) method.invoke(target, draft, intent);
  }

  private String seedAuditReport(
      String cityCode,
      String billingPointCode,
      String billingPointName,
      String period,
      String analysis) {
    long snapshotDbId = seedSnapshot(cityCode, billingPointCode, billingPointName, period);
    long wordFileId = seedStoredFile("word-" + billingPointCode + "-" + period + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    long pdfFileId = seedStoredFile("pdf-" + billingPointCode + "-" + period + ".pdf", "application/pdf");
    String reportPublicId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO audit_report
          (public_id, report_number, billing_point_snapshot_id, source_type, status, title,
           situation, analysis, rectification, word_file_id, pdf_file_id,
           business_snapshot_json, updated_by)
        VALUES (?, ?, ?, 'HISTORICAL_IMPORT', 'FORMALIZED', ?, ?, ?, ?, ?, ?, '{}',
                'city-memory-test')
        """,
        reportPublicId,
        "TEST-" + UUID.randomUUID().toString().substring(0, 24),
        snapshotDbId,
        billingPointName + "电费稽核说明",
        "本期出现环比超标。",
        analysis,
        "已完成现场核查，后续持续复核。",
        wordFileId,
        pdfFileId);
    Long reportId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM audit_report WHERE public_id=?", Long.class, reportPublicId);
    jdbcTemplate.update(
        """
        INSERT INTO historical_audit_case
          (public_id, report_id, city_code, billing_point_code, data_period, over_limit_type,
           final_reason, summary, trust_level, image_count, image_analysis_status,
           image_analysis_text)
        VALUES (?, ?, ?, ?, ?, 'ONLY_MOM', ?, ?, 'CONFIRMED_REPORT', 1, 'COMPLETED', ?)
        """,
        UUID.randomUUID().toString(),
        reportId,
        cityCode,
        billingPointCode,
        period,
        analysis,
        analysis,
        "历史图片显示设备和空调状态。");
    return reportPublicId;
  }

  private long seedSnapshot(
      String cityCode, String billingPointCode, String billingPointName, String period) {
    long importDbId = seedImportJob(cityCode, period);
    String snapshotId = UUID.randomUUID().toString();
    String periodStart = period + "-01";
    String periodEnd = period + "-28";
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_snapshot
          (public_id, data_period, period_start, period_end, city_code,
           source_import_job_id, source_row_no, raw_row_json, billing_point_code,
           billing_point_name, city_name, data_json)
        VALUES (?, ?, ?, ?, ?, ?, 1, '{}', ?, ?, ?, '{}')
        """,
        snapshotId,
        period,
        periodStart,
        periodEnd,
        cityCode,
        importDbId,
        billingPointCode,
        billingPointName,
        cityName(cityCode));
    jdbcTemplate.update(
        """
        INSERT INTO audit_result
          (public_id, billing_point_code, billing_point_name, city_code, data_period,
           period_start, period_end, audit_status, report_status, over_limit_type,
           max_ratio, detail_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'OVER_LIMIT', 'WAITING', 'ONLY_MOM', 18.5, '{}')
        """,
        UUID.randomUUID().toString(),
        billingPointCode,
        billingPointName,
        cityCode,
        period,
        periodStart,
        periodEnd);
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM billing_point_snapshot WHERE public_id=?", Long.class, snapshotId);
    if (id == null) throw new IllegalStateException("Snapshot was not inserted");
    return id;
  }

  private long seedImportJob(String cityCode, String period) {
    long storedFileId = seedStoredFile("seed-" + cityCode + "-" + period + ".csv", "text/csv");
    String importId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO import_job
          (public_id, dataset_type, data_period, city_code, status, source_file_id,
           task_public_id, errors_json, created_by, updated_by)
        VALUES (?, 'BILLING_POINT', ?, ?, 'SUCCEEDED', ?, ?, '[]',
                'city-memory-test', 'city-memory-test')
        """,
        importId,
        period,
        cityCode,
        storedFileId,
        UUID.randomUUID().toString());
    Long id =
        jdbcTemplate.queryForObject("SELECT id FROM import_job WHERE public_id=?", Long.class, importId);
    if (id == null) throw new IllegalStateException("Import job was not inserted");
    return id;
  }

  private void seedPositionPointMaster(String billingPointCode, String billingPointName) {
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_master
          (billing_point_code, billing_point_name, city_code, resource_summary_json)
        VALUES (?, ?, '320100',
                '{"资源类型":"位置点","主设备功率":"2.549","空调总功率":"2.12","铁塔空调总额定功率":"-"}')
        """,
        billingPointCode,
        billingPointName);
  }

  private long seedStoredFile(String originalName, String mediaType) {
    String publicId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO stored_file
          (public_id, storage_name, original_name, media_type, byte_size, sha256,
           purpose, created_by)
        VALUES (?, ?, ?, ?, 1, ?, 'TEST', 'city-memory-test')
        """,
        publicId,
        publicId + "-" + originalName,
        originalName,
        mediaType,
        publicId.replace("-", "").substring(0, 32).repeat(2));
    Long id =
        jdbcTemplate.queryForObject("SELECT id FROM stored_file WHERE public_id=?", Long.class, publicId);
    if (id == null) throw new IllegalStateException("Stored file was not inserted");
    return id;
  }

  private String cityName(String cityCode) {
    return "320500".equals(cityCode) ? "苏州市" : "南京市";
  }

  private String seedOverLimitSnapshot() {
    String fileId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO stored_file
          (public_id, storage_name, original_name, media_type, byte_size, sha256, purpose, created_by)
        VALUES (?, ?, 'seed.csv', 'text/csv', 1, ?, 'TEST', 'city-memory-test')
        """,
        fileId,
        fileId + ".csv",
        "0".repeat(64));
    Long storedFileId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM stored_file WHERE public_id=?", Long.class, fileId);
    String importId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO import_job
          (public_id, dataset_type, data_period, city_code, status, source_file_id,
           task_public_id, errors_json, created_by, updated_by)
        VALUES (?, 'BILLING_POINT', '2026-07', '320100', 'SUCCEEDED', ?, ?, '[]',
                'city-memory-test', 'city-memory-test')
        """,
        importId,
        storedFileId,
        UUID.randomUUID().toString());
    Long importDbId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM import_job WHERE public_id=?", Long.class, importId);
    String snapshotId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_snapshot
          (public_id, data_period, period_start, period_end, city_code,
           source_import_job_id, source_row_no, raw_row_json, billing_point_code,
           billing_point_name, city_name, data_json)
        VALUES (?, '2026-07', '2026-07-01', '2026-07-31', '320100', ?, 1, '{}',
                'MEMORY-POINT-001', '南京测试报账点', '南京市', '{}')
        """,
        snapshotId,
        importDbId);
    jdbcTemplate.update(
        """
        INSERT INTO audit_result
          (public_id, billing_point_code, billing_point_name, city_code, data_period,
           period_start, period_end, audit_status, report_status, over_limit_type,
           max_ratio, detail_json)
        VALUES (?, 'MEMORY-POINT-001', '南京测试报账点', '320100', '2026-07',
                '2026-07-01', '2026-07-31', 'OVER_LIMIT', 'WAITING', 'ONLY_MOM', 18.5, '{}')
        """,
        UUID.randomUUID().toString());
    return snapshotId;
  }
}
