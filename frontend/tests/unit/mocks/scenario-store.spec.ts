import { createScenarioStore } from "@/mocks/scenario-store";

describe("complete-system mock scenario", () => {
  it("resets to the same deterministic fixture", () => {
    const scenario = createScenarioStore();
    const initial = scenario.snapshot();

    scenario.completeImport(
      scenario.createImport({
        datasetType: "BILLING_POINT",
        period: "2026-07",
        fileName: "billing-points.xlsx",
      }).id,
      "SUCCESS",
    );
    scenario.reset();

    expect(scenario.snapshot()).toEqual(initial);
  });

  it("forms the current period only after all four active batches succeed", () => {
    const scenario = createScenarioStore();
    const types = [
      "BILLING_POINT",
      "PAYMENT",
      "METER_READING",
      "BENCHMARK",
    ] as const;

    for (const datasetType of types.slice(0, 3)) {
      const job = scenario.createImport({
        datasetType,
        period: "2026-07",
        fileName: `${datasetType}.xlsx`,
      });
      scenario.completeImport(job.id, "SUCCESS");
    }
    expect(scenario.getDashboard().currentDataPeriod).toBe("2026-06");

    const fourth = scenario.createImport({
      datasetType: types[3],
      period: "2026-07",
      fileName: "benchmark.csv",
    });
    scenario.completeImport(fourth.id, "SUCCESS");

    expect(scenario.getDashboard().currentDataPeriod).toBe("2026-07");
  });

  it("supersedes an active batch only after a replacement succeeds", () => {
    const scenario = createScenarioStore();
    const first = scenario.createImport({
      datasetType: "PAYMENT",
      period: "2026-06",
      fileName: "payment-v1.xlsx",
    });
    scenario.completeImport(first.id, "SUCCESS");

    const failed = scenario.createImport({
      datasetType: "PAYMENT",
      period: "2026-06",
      fileName: "payment-broken.xlsx",
    });
    scenario.completeImport(failed.id, "FAILED");
    expect(scenario.getImport(first.id)?.status).toBe("ACTIVE");

    const replacement = scenario.createImport({
      datasetType: "PAYMENT",
      period: "2026-06",
      fileName: "payment-v2.xlsx",
    });
    scenario.completeImport(replacement.id, "SUCCESS");

    expect(scenario.getImport(first.id)?.status).toBe("SUPERSEDED");
    expect(scenario.getImport(replacement.id)?.status).toBe("ACTIVE");
  });

  it("filters and paginates billing points without losing query state", () => {
    const scenario = createScenarioStore();
    const result = scenario.listBillingPoints({
      cityCode: "320100",
      period: "2026-06",
      keyword: "南京",
      auditStatus: "OVER_LIMIT",
      page: 1,
      size: 1,
    });

    expect(result.page).toBe(1);
    expect(result.size).toBe(1);
    expect(result.totalElements).toBeGreaterThan(1);
    expect(result.items).toHaveLength(1);
    expect(result.items[0]?.city.code).toBe("320100");
    expect(result.items[0]?.auditStatus).toBe("OVER_LIMIT");
  });

  it("creates draft versions for edits, images and restoration but not questions", () => {
    const scenario = createScenarioStore();
    const draft = scenario.getDraft("draft-1");
    expect(draft).toBeDefined();
    const initialVersions = draft?.versions.length ?? 0;

    scenario.sendDraftMessage("draft-1", {
      intent: "ASK",
      content: "本月为什么超标？",
      imageNames: [],
    });
    expect(scenario.getDraft("draft-1")?.versions).toHaveLength(
      initialVersions,
    );

    scenario.sendDraftMessage("draft-1", {
      intent: "EDIT",
      content: "将整改建议改为分阶段执行。",
      imageNames: [],
    });
    scenario.sendDraftMessage("draft-1", {
      intent: "IMAGE_ANALYSIS",
      content: "分析现场照片。",
      imageNames: ["现场照片.png"],
    });
    expect(scenario.getDraft("draft-1")?.versions).toHaveLength(
      initialVersions + 2,
    );

    scenario.restoreDraftVersion("draft-1", "draft-version-1");
    expect(scenario.getDraft("draft-1")?.versions).toHaveLength(
      initialVersions + 3,
    );
  });

  it("keeps the formal report number when applying a reasoned correction", () => {
    const scenario = createScenarioStore();
    const before = scenario.getReport("report-1");

    expect(() => scenario.correctReport("report-1", "")).toThrow(
      "CORRECTION_REASON_REQUIRED",
    );
    const corrected = scenario.correctReport(
      "report-1",
      "复核后采用最新重算结果",
    );

    expect(corrected.reportNumber).toBe(before?.reportNumber);
    expect(corrected.correctionCount).toBe((before?.correctionCount ?? 0) + 1);
  });

  it("uses the unified BG monthly six-digit report number contract", () => {
    const scenario = createScenarioStore();
    const generated = scenario.generateFormalReport("draft-1");
    const candidates = scenario.listHistoricalCandidates({
      cityCode: "320100",
      keyword: "南京中心广场",
    });
    const centerPeriods = candidates
      .filter((item) => item.billingPointCode === "320100-BP-0001")
      .map((item) => item.period);
    expect(centerPeriods).toEqual(["2026-04"]);
    const centerMissingPeriod = candidates.find(
      (item) =>
        item.billingPointCode === "320100-BP-0001" &&
        item.period === "2026-04",
    );
    const imported = scenario.importHistoricalReport({
      billingPointPeriodId: centerMissingPeriod?.billingPointPeriodId ?? "",
      fileName: "历史报告.docx",
    });

    expect(generated.reportNumber).toMatch(/^BG-\d{6}-\d{6}$/);
    expect(imported.reportNumber).toMatch(/^BG-\d{6}-\d{6}$/);
    expect(generated.reportNumber).not.toBe(imported.reportNumber);
  });

  it("supports creating, editing, resetting, disabling and enabling a city user", () => {
    const scenario = createScenarioStore();
    const created = scenario.createUser({
      username: "test_city_user",
      displayName: "测试地市用户",
      cityCode: "320100",
      enabled: true,
      initialPassword: "123456",
      confirmPassword: "123456",
    });

    expect(created.enabled).toBe(true);
    expect(
      scenario.updateUser(created.id, { displayName: "更新后的用户" })
        .displayName,
    ).toBe("更新后的用户");
    expect(scenario.resetUserPassword(created.id).mustChangePassword).toBe(
      false,
    );
    expect(scenario.setUserEnabled(created.id, false).enabled).toBe(false);
    expect(scenario.setUserEnabled(created.id, true).enabled).toBe(true);
  });
});
