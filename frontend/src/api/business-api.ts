import { httpClient } from "@/api";
import type {
  BenchmarkRule,
  BillingPointDetail,
  BillingPointQuery,
  BusinessField,
  BusinessCity,
  CreateImportInput,
  DashboardData,
  DraftBlock,
  DraftVersion,
  ExportJob,
  ImportBatch,
  ManagedUser,
  PageResult,
  PaymentRecord,
  MeterRecord,
  BenchmarkRecord,
  ReportDraft,
  ReportQuery,
  ReportSummary,
  ReportStatus,
  SendDraftMessageInput,
} from "@/types/business";

function asResult<T>(value: unknown): T {
  return value as T;
}

interface BackendPage<T> {
  items: T[];
  page: number;
  size: number;
  totalElements?: number;
  total?: number;
  totalPages: number;
}

function mapDashboard(value: Partial<DashboardData>): DashboardData {
  return {
    currentDataPeriod: value.currentDataPeriod ?? null,
    availablePeriods: value.availablePeriods ?? [],
    imports: value.imports ?? [
      { datasetType: "BILLING_POINT", activeBatch: null },
      { datasetType: "PAYMENT", activeBatch: null },
      { datasetType: "METER_READING", activeBatch: null },
      { datasetType: "BENCHMARK", activeBatch: null },
    ],
    cityCount: value.cityCount ?? 0,
    siteCount: value.siteCount ?? 0,
    lastUpdatedAt: value.lastUpdatedAt ?? null,
    billingPointCount: value.billingPointCount ?? 0,
    normalBillingPointCount: value.normalBillingPointCount ?? 0,
    overLimitBillingPointCount: value.overLimitBillingPointCount ?? 0,
    pendingReviewCount: value.pendingReviewCount ?? 0,
    draftReportCount: value.draftReportCount ?? 0,
    finalReportCount: value.finalReportCount ?? 0,
    districtOverLimitCounts: value.districtOverLimitCounts ?? [],
    overLimitTypeCounts: (value.overLimitTypeCounts ?? []).map((item) => ({
      ...item,
      name: overLimitTypeLabel(item.name) ?? item.name,
    })),
    pendingTasks: (value.pendingTasks ?? []).map((item) => ({
      ...item,
      overLimitType: overLimitTypeLabel(item.overLimitType) ?? item.overLimitType,
    })),
  };
}

interface BackendBillingPointSummary {
  id: string;
  code: string;
  name: string;
  city: BusinessCity;
  district: string | null;
  siteName: string | null;
  electricityCategory: string | null;
  billingPointStatus: string | null;
  period: string;
  periodStart: string | null;
  periodEnd: string | null;
  paymentCodes: string[];
  paymentEligible: boolean;
  actualEnergy: string | null;
  actualAmount: string | null;
  benchmarkEnergy: string | null;
  maxDeviationRate: string | null;
  auditStatus: string;
  overLimitType: string | null;
  reportStatus: "PENDING" | "GENERATED" | "NONE";
  draftId: string | null;
  reportId: string | null;
  reportNumber: string | null;
}

interface BackendBillingPointDetail {
  summary: BackendBillingPointSummary;
  overviewGroups: Array<{
    name: string;
    fields: Array<{ name: string; sourceName: string; value: string | null }>;
  }>;
  payments: unknown[];
  meters: unknown[];
  benchmarks: unknown[];
  audit: unknown;
  draftId: string | null;
  reportId: string | null;
}

interface BackendFieldValue {
  order?: number;
  name?: string;
  sourceName?: string;
  group?: string;
  type?: string;
  value?: string | null;
}

interface BackendFieldGroup {
  title?: string;
  name?: string;
  fields?: BackendFieldValue[];
}

interface BackendRecordDetail {
  id: string;
  paymentCode?: string | null;
  meterCode?: string | null;
  fieldGroups?: BackendFieldGroup[];
  fields?: BackendFieldValue[];
}

interface ReportSections {
  title: string;
  situation: string;
  analysis: string;
  rectification: string;
}

interface BackendDraftMessage {
  id: string;
  intent: "ASK" | "EDIT" | "IMAGE_ANALYSIS";
  userContent: string;
  assistantContent: string;
  changedDraft: boolean;
  imageFileIds: string[];
  createdAt: string;
}

interface BackendDraftVersion {
  id: string;
  version: number;
  changeType: "INITIAL" | "EDIT" | "IMAGE_ANALYSIS" | "RESTORE" | "MANUAL";
  sections: ReportSections;
  imageFileIds: string[];
  createdAt: string;
  createdBy: string;
}

interface BackendReportDraft {
  id: string;
  billingPointPeriodId: string;
  billingPointCode: string;
  billingPointName: string;
  cityCode: string;
  period: string;
  auditStatus: string;
  status: "DRAFT" | "CORRECTING" | "GENERATING" | "FORMALIZED";
  sections: ReportSections;
  currentVersion: number;
  currentImageFileIds: string[];
  formalReportId: string | null;
  messages: BackendDraftMessage[];
  createdAt: string;
  updatedAt: string;
  version: number;
}

interface BackendTask {
  id: string;
  status: "QUEUED" | "RUNNING" | "RETRY_WAIT" | "SUCCEEDED" | "FAILED";
  errorCode: string | null;
  result: { reportId?: string } | null;
}

interface BackendReportSummary {
  id: string;
  reportNumber: string;
  billingPointCode: string;
  billingPointName: string;
  cityCode: string;
  cityName: string;
  district: string | null;
  period: string;
  sourceType: "SYSTEM" | "HISTORICAL";
  status: "GENERATED" | "CORRECTED";
  actualEnergy: string | null;
  actualAmount: string | null;
  overLimitType: string | null;
  maxRatio: string | null;
  generatedAt: string;
  updatedAt: string;
  version: number;
  sections?: ReportSections;
  correctionReason?: string | null;
  correctedAt?: string | null;
}

function queryString(values: Record<string, string | number>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (String(value).length > 0) params.set(key, String(value));
  }
  return params.toString();
}

function mapReportStatus(
  status: BackendBillingPointSummary["reportStatus"],
): ReportStatus {
  if (status === "PENDING") return "DRAFT";
  if (status === "GENERATED") return "FINAL";
  return "NONE";
}

function overLimitTypeLabel(value: string | null): string | null {
  if (value === null || value.length === 0 || /^\?+$/.test(value)) {
    return null;
  }
  const labels: Record<string, string> = {
    ONLY_YOY: "同比超标",
    ONLY_MOM: "环比超标",
    ONLY_RATED: "额定标杆超标",
    MULTIPLE: "多项超标",
    NONE: "未超标",
  };
  return labels[value] ?? value;
}

function mapBillingSummary(
  item: BackendBillingPointSummary,
): BillingPointDetail["summary"] {
  return {
    id: item.id,
    code: item.code,
    name: item.name,
    city: item.city,
    period: item.period,
    address: item.siteName ?? item.district ?? "",
    district: item.district,
    siteName: item.siteName,
    electricityCategory: item.electricityCategory ?? "",
    billingPointStatus: item.billingPointStatus,
    paymentEligibility: item.paymentEligible ? "ELIGIBLE" : "PENDING",
    actualEnergy: item.actualEnergy,
    actualAmount: item.actualAmount,
    benchmarkEnergy: item.benchmarkEnergy,
    deviationRate: item.maxDeviationRate,
    overLimitType: overLimitTypeLabel(item.overLimitType),
    auditStatus:
      item.auditStatus as BillingPointDetail["summary"]["auditStatus"],
    reportStatus: mapReportStatus(item.reportStatus),
    draftId: item.draftId,
    reportId: item.reportId,
    reportNumber: item.reportNumber,
    periodStart: item.periodStart,
    periodEnd: item.periodEnd,
    paymentCodes: item.paymentCodes,
  };
}

function mapBillingDetail(item: BackendBillingPointDetail): BillingPointDetail {
  return {
    summary: mapBillingSummary(item.summary),
    overviewGroups: mapFieldGroups(item.overviewGroups),
    payments: (item.payments as BackendRecordDetail[]).map(mapPaymentRecord),
    meters: (item.meters as BackendRecordDetail[]).map(mapMeterRecord),
    benchmarks: (item.benchmarks as BackendRecordDetail[]).map(mapBenchmarkRecord),
    audit: item.audit as BillingPointDetail["audit"],
    draftId: item.draftId,
  };
}

function mapFieldGroups(groups: BackendFieldGroup[]): BillingPointDetail["overviewGroups"] {
  return groups.map((group) => ({
    title: group.title ?? group.name ?? "字段信息",
    fields: (group.fields ?? []).map(mapBusinessField),
  }));
}

function mapBusinessField(field: BackendFieldValue): BusinessField {
  return {
    key: field.name ?? field.sourceName ?? `field-${field.order ?? 0}`,
    label: field.sourceName ?? field.name ?? "字段",
    value: field.value ?? "—",
  };
}

function fieldFromGroups(groups: BillingPointDetail["overviewGroups"], labels: string[]): string {
  for (const group of groups) {
    const found = group.fields.find((field) => labels.includes(field.label));
    if (found?.value && found.value !== "—") return found.value;
  }
  return "—";
}

function approvalStatus(value: string): PaymentRecord["approvalStatus"] {
  if (/驳回|拒绝|退回|不通过/.test(value)) return "REJECTED";
  if (/通过|完成|已审/.test(value)) return "APPROVED";
  return "PENDING";
}

function mapPaymentRecord(record: BackendRecordDetail): PaymentRecord {
  const groups = mapFieldGroups(record.fieldGroups ?? []);
  const billNumber = record.paymentCode ?? fieldFromGroups(groups, ["缴费单编码"]);
  const approval = fieldFromGroups(groups, ["审核状态"]);
  return {
    id: record.id,
    paymentCode: record.paymentCode ?? null,
    billNumber,
    payee: fieldFromGroups(groups, ["供应商名称", "收款单位"]),
    electricityFee: fieldFromGroups(groups, ["实际报账金额", "实际价款"]),
    billingEnergy: fieldFromGroups(groups, ["实际总耗电量"]),
    approvalStatus: approvalStatus(approval),
    eligible: approvalStatus(approval) === "APPROVED",
    fieldGroups: groups,
    fields: (record.fields ?? []).map(mapBusinessField),
  };
}

function mapMeterRecord(record: BackendRecordDetail): MeterRecord {
  const groups = mapFieldGroups(record.fieldGroups ?? []);
  return {
    id: record.id,
    paymentCode: record.paymentCode ?? null,
    meterCode: record.meterCode ?? null,
    meterNumber: record.meterCode ?? fieldFromGroups(groups, ["电表编码"]),
    previousReading: fieldFromGroups(groups, ["电表上期读数"]),
    currentReading: fieldFromGroups(groups, ["本期读数"]),
    multiplier: fieldFromGroups(groups, ["电表倍率"]),
    allocatedEnergy: fieldFromGroups(groups, ["分摊后度数"]),
    valid: true,
    fieldGroups: groups,
    fields: (record.fields ?? []).map(mapBusinessField),
  };
}

function mapBenchmarkRecord(record: BackendRecordDetail): BenchmarkRecord {
  const groups = mapFieldGroups(record.fieldGroups ?? []);
  return {
    id: record.id,
    benchmarkType: fieldFromGroups(groups, ["标杆类型"]),
    value: fieldFromGroups(groups, ["月总标杆", "标杆值"]),
    effectiveFrom: fieldFromGroups(groups, ["年份", "月份"]),
    ruleVersion: fieldFromGroups(groups, ["规则版本"]),
    fieldGroups: groups,
  };
}

function sectionsToBlocks(sections: ReportSections): DraftBlock[] {
  return [
    {
      id: "title",
      type: "HEADING",
      title: "报告标题",
      content: sections.title,
    },
    {
      id: "situation",
      type: "SITUATION",
      title: "情况说明",
      content: sections.situation,
    },
    {
      id: "analysis",
      type: "ANALYSIS",
      title: "审计分析",
      content: sections.analysis,
    },
    {
      id: "rectification",
      type: "RECTIFICATION",
      title: "整改建议",
      content: sections.rectification,
    },
  ];
}

function blocksToSections(blocks: DraftBlock[]): ReportSections {
  return {
    title:
      blocks.find((block) => block.type === "HEADING")?.content ??
      "电费稽核报告",
    situation:
      blocks.find((block) => block.type === "SITUATION")?.content ?? "",
    analysis: blocks.find((block) => block.type === "ANALYSIS")?.content ?? "",
    rectification:
      blocks.find((block) => block.type === "RECTIFICATION")?.content ?? "",
  };
}

function mapDraftVersion(version: BackendDraftVersion): DraftVersion {
  return {
    id: version.id,
    version: version.version,
    reason: version.changeType === "MANUAL" ? "EDIT" : version.changeType,
    summary: version.changeType,
    createdAt: version.createdAt,
    blocks: sectionsToBlocks(version.sections),
  };
}

function mapDraft(
  item: BackendReportDraft,
  versions: DraftVersion[] = [],
): ReportDraft {
  return {
    id: item.id,
    billingPointId: item.billingPointPeriodId,
    billingPointCode: item.billingPointCode,
    billingPointName: item.billingPointName,
    city: { code: item.cityCode, name: item.cityCode },
    period: item.period,
    status:
      item.status === "GENERATING"
        ? "GENERATING"
        : item.status === "FORMALIZED"
          ? "FINALIZED"
          : "EDITING",
    blocks: sectionsToBlocks(item.sections),
    messages: item.messages.flatMap((message) => [
      {
        id: `${message.id}-user`,
        role: "USER" as const,
        intent: message.intent,
        content: message.userContent,
        imageNames: message.imageFileIds,
        createdAt: message.createdAt,
      },
      {
        id: `${message.id}-assistant`,
        role: "ASSISTANT" as const,
        intent: message.intent,
        content: message.assistantContent,
        imageNames: [],
        createdAt: message.createdAt,
      },
    ]),
    versions,
    updatedAt: item.updatedAt,
    formalReportId: item.formalReportId,
    entityVersion: item.version,
  };
}

function mapReport(item: BackendReportSummary): ReportSummary {
  const summary = item.sections
    ? [
        item.sections.situation,
        item.sections.analysis,
        item.sections.rectification,
      ].join("\n")
    : `${item.billingPointName} ${item.period} 电费稽核报告`;
  return {
    id: item.id,
    reportNumber: item.reportNumber,
    billingPointId: item.id,
    billingPointCode: item.billingPointCode,
    billingPointName: item.billingPointName,
    city: { code: item.cityCode, name: item.cityName },
    district: item.district,
    period: item.period,
    status: item.status === "CORRECTED" ? "CORRECTED" : "FINAL",
    source: item.sourceType === "HISTORICAL" ? "HISTORICAL_IMPORT" : "SYSTEM",
    generatedAt: item.generatedAt,
    correctedAt: item.correctedAt ?? null,
    correctionCount: item.status === "CORRECTED" ? 1 : 0,
    actualEnergy: item.actualEnergy,
    actualAmount: item.actualAmount,
    overLimitType: overLimitTypeLabel(item.overLimitType),
    maxRatio: item.maxRatio,
    wordFileName: `${item.reportNumber}.docx`,
    pdfFileName: `${item.reportNumber}.pdf`,
    summary,
    archivedAudit: [],
    latestAudit: [],
    corrections: item.correctionReason
      ? [
          {
            reason: item.correctionReason,
            operator: "",
            occurredAt: item.correctedAt ?? item.updatedAt,
            summary: "报告已更正",
          },
        ]
      : [],
  };
}

async function waitForReport(taskId: string): Promise<string> {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const task = asResult<BackendTask>(
      await httpClient.get(`/api/v1/tasks/${encodeURIComponent(taskId)}`),
    );
    if (task.status === "SUCCEEDED" && task.result?.reportId) {
      return task.result.reportId;
    }
    if (task.status === "FAILED") {
      throw new Error(task.errorCode ?? "正式报告生成失败");
    }
    await new Promise((resolve) => globalThis.setTimeout(resolve, 1_000));
  }
  throw new Error("正式报告生成超时，请稍后在历史报告中查看任务结果");
}

async function fetchReportById(id: string): Promise<ReportSummary> {
  return mapReport(
    asResult<BackendReportSummary>(
      await httpClient.get(`/api/v1/reports/${encodeURIComponent(id)}`),
    ),
  );
}

export const businessApi = {
  isMockMode: false,
  reset(): void {
    // 真实业务运行不在 API 层注入假数据。
  },
  cities: {
    async list(): Promise<BusinessCity[]> {
      return asResult<BusinessCity[]>(await httpClient.get("/api/v1/cities"));
    },
  },
  dashboard: {
    async get(cityCode?: string, period?: string): Promise<DashboardData> {
      const query = queryString({
        cityCode: cityCode ?? "",
        period: period ?? "",
      });
      return mapDashboard(
        asResult<Partial<DashboardData>>(
          await httpClient.get(`/api/v1/dashboard/summary${query ? `?${query}` : ""}`),
        ),
      );
    },
  },
  imports: {
    async list(): Promise<ImportBatch[]> {
      const response = asResult<PageResult<ImportBatch>>(
        await httpClient.get("/api/v1/import-batches?page=0&size=100"),
      );
      return response.items;
    },
    async get(id: string): Promise<ImportBatch | undefined> {
      return asResult<ImportBatch>(
        await httpClient.get(
          `/api/v1/import-batches/${encodeURIComponent(id)}`,
        ),
      );
    },
    async create(input: CreateImportInput, file: File): Promise<ImportBatch> {
      const form = new FormData();
      form.set("datasetType", input.datasetType);
      form.set("period", input.period);
      form.set("file", file);
      return asResult<ImportBatch>(
        await httpClient.postForm("/api/v1/import-batches", form),
      );
    },
    async retry(id: string): Promise<ImportBatch> {
      return asResult<ImportBatch>(
        await httpClient.post(
          `/api/v1/import-batches/${encodeURIComponent(id)}/retries`,
          {},
        ),
      );
    },
  },
  exportJobs: {
    async create(input: {
      period: string;
      cityCode: string;
      datasetTypes: string[];
      billingPointIds: string[];
    }): Promise<ExportJob> {
      return asResult<ExportJob>(
        await httpClient.post(
          "/api/v1/export-jobs",
          input,
          { headers: { "Idempotency-Key": crypto.randomUUID() } },
        ),
      );
    },
    async get(id: string): Promise<ExportJob> {
      return asResult<ExportJob>(
        await httpClient.get(`/api/v1/export-jobs/${encodeURIComponent(id)}`),
      );
    },
    async download(job: ExportJob): Promise<Blob> {
      if (job.downloadUrl === null) {
        throw new Error("导出任务尚未生成下载文件");
      }
      return httpClient.getBlob(job.downloadUrl);
    },
  },
  billingPoints: {
    async list(
      query: BillingPointQuery,
    ): Promise<PageResult<BillingPointDetail["summary"]>> {
      const params = queryString({
        cityCode: query.cityCode,
        period: query.period,
        keyword: query.keyword,
        auditStatus: query.auditStatus,
        page: Math.max(0, query.page - 1),
        size: query.size,
      });
      const result = asResult<BackendPage<BackendBillingPointSummary>>(
        await httpClient.get(`/api/v1/billing-point-periods?${params}`),
      );
      return {
        items: result.items.map(mapBillingSummary),
        page: result.page + 1,
        size: result.size,
        totalElements: result.totalElements ?? result.total ?? 0,
        totalPages: result.totalPages,
      };
    },
    async get(id: string): Promise<BillingPointDetail | undefined> {
      return mapBillingDetail(
        asResult<BackendBillingPointDetail>(
          await httpClient.get(
            `/api/v1/billing-point-periods/${encodeURIComponent(id)}`,
          ),
        ),
      );
    },
  },
  drafts: {
    async createOrResume(billingPointPeriodId: string): Promise<ReportDraft> {
      const raw = asResult<BackendReportDraft>(
        await httpClient.post("/api/v1/report-drafts", {
          billingPointPeriodId,
        }),
      );
      const versions = asResult<BackendDraftVersion[]>(
        await httpClient.get(
          `/api/v1/report-drafts/${encodeURIComponent(raw.id)}/versions`,
        ),
      ).map(mapDraftVersion);
      return mapDraft(raw, versions);
    },
    async get(id: string): Promise<ReportDraft | undefined> {
      const raw = asResult<BackendReportDraft>(
        await httpClient.get(`/api/v1/report-drafts/${encodeURIComponent(id)}`),
      );
      const versions = asResult<BackendDraftVersion[]>(
        await httpClient.get(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/versions`,
        ),
      ).map(mapDraftVersion);
      return mapDraft(raw, versions);
    },
    async save(id: string, draft: ReportDraft): Promise<ReportDraft> {
      const raw = asResult<BackendReportDraft>(
        await httpClient.patch(
          `/api/v1/report-drafts/${encodeURIComponent(id)}`,
          blocksToSections(draft.blocks),
          { headers: { "If-Match": String(draft.entityVersion) } },
        ),
      );
      const versions = asResult<BackendDraftVersion[]>(
        await httpClient.get(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/versions`,
        ),
      ).map(mapDraftVersion);
      return mapDraft(raw, versions);
    },
    async uploadImage(id: string, file: File): Promise<string> {
      const form = new FormData();
      form.set("file", file);
      const response = asResult<{ fileId: string }>(
        await httpClient.postForm(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/images`,
          form,
        ),
      );
      return response.fileId;
    },
    async sendMessage(
      id: string,
      input: SendDraftMessageInput,
      version?: number,
    ): Promise<ReportDraft> {
      const raw = asResult<BackendReportDraft>(
        await httpClient.post(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/messages`,
          {
            intent: input.intent,
            content: input.content,
            imageFileIds: input.imageFileIds ?? [],
          },
          { headers: { "If-Match": String(version ?? 0) } },
        ),
      );
      const versions = asResult<BackendDraftVersion[]>(
        await httpClient.get(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/versions`,
        ),
      ).map(mapDraftVersion);
      return mapDraft(raw, versions);
    },
    async restore(id: string, versionId: string): Promise<ReportDraft> {
      const current = await this.get(id);
      const raw = asResult<BackendReportDraft>(
        await httpClient.post(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/restorations`,
          {},
          { headers: { "If-Match": String(current?.entityVersion ?? 0) } },
        ),
      );
      const versions = asResult<BackendDraftVersion[]>(
        await httpClient.get(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/versions`,
        ),
      ).map(mapDraftVersion);
      return mapDraft(raw, versions);
    },
    async generate(id: string): Promise<ReportSummary> {
      const current = await this.get(id);
      const task = asResult<{ taskId: string }>(
        await httpClient.post(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/formal-report`,
          {},
          { headers: { "If-Match": String(current?.entityVersion ?? 0) } },
        ),
      );
      const reportId = await waitForReport(task.taskId);
      return fetchReportById(reportId);
    },
  },
  reports: {
    async list(query: ReportQuery): Promise<PageResult<ReportSummary>> {
      const params = queryString({
        cityCode: query.cityCode,
        district: query.district,
        period: query.period,
        keyword: query.keyword,
        source: query.source,
        page: Math.max(0, query.page - 1),
        size: query.size,
      });
      const result = asResult<BackendPage<BackendReportSummary>>(
        await httpClient.get(`/api/v1/reports?${params}`),
      );
      return {
        items: result.items.map(mapReport),
        page: result.page + 1,
        size: result.size,
        totalElements: result.totalElements ?? result.total ?? 0,
        totalPages: result.totalPages,
      };
    },
    async get(id: string): Promise<ReportSummary | undefined> {
      return fetchReportById(id);
    },
    async importHistorical(input: {
      billingPointId: string;
      period: string;
      file: File;
    }): Promise<ReportSummary> {
      const form = new FormData();
      form.set("billingPointPeriodId", input.billingPointId);
      form.set("file", input.file);
      const created = asResult<{ reportPublicId?: string; taskId?: string }>(
        await httpClient.postForm("/api/v1/historical-report-imports", form),
      );
      if (created.reportPublicId !== undefined) {
        return fetchReportById(created.reportPublicId);
      }
      if (created.taskId !== undefined) {
        return fetchReportById(await waitForReport(created.taskId));
      }
      throw new Error("历史报告导入任务未返回报告编号");
    },
    async correct(
      id: string,
      input: { reason: string; correctedSummary?: string; file?: File },
    ): Promise<ReportSummary> {
      if (input.file !== undefined) {
        const form = new FormData();
        form.set("reason", input.reason);
        form.set("file", input.file);
        return asResult<ReportSummary>(
          await httpClient.postForm(
            `/api/v1/audit-reports/${encodeURIComponent(id)}/corrections`,
            form,
          ),
        );
      }
      return asResult<ReportSummary>(
        await httpClient.post(
          `/api/v1/audit-reports/${encodeURIComponent(id)}/corrections`,
          {
            reason: input.reason,
            ...(input.correctedSummary === undefined
              ? {}
              : { correctedSummary: input.correctedSummary }),
          },
        ),
      );
    },
    async downloadWord(id: string): Promise<Blob> {
      return httpClient.getBlob(
        `/api/v1/reports/${encodeURIComponent(id)}/word`,
      );
    },
  },
  users: {
    async list(page: number, size: number): Promise<PageResult<ManagedUser>> {
      const result = asResult<PageResult<ManagedUser>>(
        await httpClient.get(
          `/api/v1/users?page=${Math.max(0, page - 1)}&size=${size}`,
        ),
      );
      return { ...result, page: result.page + 1 };
    },
    async create(input: {
      username: string;
      displayName: string;
      cityCode: string;
    }): Promise<ManagedUser> {
      return asResult<ManagedUser>(
        await httpClient.post("/api/v1/users", input),
      );
    },
    async update(id: string, displayName: string): Promise<ManagedUser> {
      return asResult<ManagedUser>(
        await httpClient.patch(`/api/v1/users/${encodeURIComponent(id)}`, {
          displayName,
        }),
      );
    },
    async resetPassword(id: string): Promise<ManagedUser> {
      return asResult<ManagedUser>(
        await httpClient.post(
          `/api/v1/users/${encodeURIComponent(id)}/password-resets`,
          {},
        ),
      );
    },
    async setEnabled(id: string, enabled: boolean): Promise<ManagedUser> {
      return asResult<ManagedUser>(
        await httpClient.patch(`/api/v1/users/${encodeURIComponent(id)}`, {
          enabled,
        }),
      );
    },
    async changeOwnPassword(
      _id: string,
      currentPassword: string,
      newPassword: string,
    ): Promise<void> {
      await httpClient.patch("/api/v1/users/current/password", {
        currentPassword,
        newPassword,
      });
    },
  },
  rules: {
    async list(): Promise<BenchmarkRule[]> {
      return asResult<BenchmarkRule[]>(
        await httpClient.get("/api/v1/benchmark-rules"),
      );
    },
  },
};

export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}
