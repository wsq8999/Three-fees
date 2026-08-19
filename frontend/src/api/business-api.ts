import { httpClient } from "@/api";
import type {
  BenchmarkRule,
  BillingPointDetail,
  BillingPointQuery,
  BusinessField,
  BusinessCity,
  CreateImportInput,
  DashboardData,
  DatasetType,
  DraftBlock,
  ExportJob,
  HistoricalReportBillingPoint,
  HistoricalReportCandidate,
  HistoricalReportPeriod,
  ImportBatch,
  ManagedUser,
  PageResult,
  PaymentRecord,
  MeterRecord,
  BenchmarkRecord,
  ReportDraft,
  ReportGenerationImageAnalysisInput,
  ReportGenerationImageAnalysisResult,
  ReportGenerationInitialContent,
  ReportQuery,
  ReportSummary,
  ReportStatus,
  SendDraftMessageInput,
} from "@/types/business";

const LONG_RUNNING_REQUEST = { timeoutMs: 0 };

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
  const pendingReportCount =
    value.pendingReportCount ?? value.draftReportCount ?? 0;
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
    draftReportCount: pendingReportCount,
    pendingReportCount,
    finalReportCount: value.finalReportCount ?? 0,
    districtOverLimitCounts: value.districtOverLimitCounts ?? [],
    overLimitTypeCounts: (value.overLimitTypeCounts ?? []).map((item) => ({
      ...item,
      name: overLimitTypeLabel(item.name) ?? item.name,
    })),
    pendingTasks: (value.pendingTasks ?? []).map((item) => ({
      ...item,
      overLimitType:
        overLimitTypeLabel(item.overLimitType) ?? item.overLimitType,
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
  intent: "ASK" | "EDIT" | "CORRECTION" | "IMAGE_ANALYSIS";
  userContent: string;
  assistantContent: string;
  changedDraft: boolean;
  imageFileIds: string[];
  createdAt: string;
}

interface BackendReportDraft {
  id: string;
  billingPointPeriodId: string;
  billingPointCode: string;
  billingPointName: string;
  cityCode: string;
  cityName: string;
  district: string | null;
  period: string;
  auditStatus: string;
  overLimitType: string | null;
  maxExceedRatio: string | null;
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

const TASK_ERROR_MESSAGES: Record<string, string> = {
  CONFIRMED_REPORT_VERSION_MISMATCH:
    "确认的报告版本已经变化，请返回工作稿检查后重新确认。",
  HISTORICAL_WORD_INVALID:
    "历史 Word 无法识别，请确认文件是真实的 .doc 或 .docx 文档。",
  HISTORICAL_REPORT_EMPTY:
    "历史 Word 暂无可在线预览内容，系统将保留原始 Word 供下载查看。",
  HISTORICAL_CONVERSION_FAILED: "历史报告导入失败，请重新上传 Word 文件。",
  TASK_PAYLOAD_INVALID: "历史报告导入任务数据异常，请重新提交。",
};

interface BackendReportSummary {
  id: string;
  reportNumber: string;
  billingPointCode: string;
  billingPointName: string;
  cityCode: string;
  cityName: string;
  district: string | null;
  period: string;
  sourceType: "SYSTEM" | "HISTORICAL" | "GENERATED" | "IMPORTED";
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

interface BackendHistoricalReportCandidate {
  billingPointPeriodId: string;
  billingPointCode: string;
  billingPointName: string;
  cityCode: string;
  cityName: string;
  period: string;
  overLimitType?: string | null;
  maxRatio?: string | null;
}

function queryString(
  values: Record<string, string | number | null | undefined>,
): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value === null || value === undefined) continue;
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

function overLimitTypeLabel(value: string | null | undefined): string | null {
  if (
    value === null ||
    value === undefined ||
    value.length === 0 ||
    /^\?+$/.test(value)
  ) {
    return null;
  }
  const labels: Record<string, string> = {
    ONLY_YOY: "仅同比超标",
    ONLY_MOM: "仅环比超标",
    ONLY_RATED: "仅额定标杆超标",
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
    benchmarks: (item.benchmarks as BackendRecordDetail[]).map(
      mapBenchmarkRecord,
    ),
    audit: item.audit as BillingPointDetail["audit"],
    draftId: item.draftId,
  };
}

function mapFieldGroups(
  groups: BackendFieldGroup[],
): BillingPointDetail["overviewGroups"] {
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

function fieldFromGroups(
  groups: BillingPointDetail["overviewGroups"],
  labels: string[],
): string {
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
  const billNumber =
    record.paymentCode ?? fieldFromGroups(groups, ["缴费单编码"]);
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
      content: cleanDraftText(sections.title),
    },
    {
      id: "situation",
      type: "SITUATION",
      title: "情况说明",
      content: looksLikeHtml(sections.situation)
        ? sections.situation
        : cleanDraftText(sections.situation),
    },
    {
      id: "analysis",
      type: "ANALYSIS",
      title: "审计分析",
      content: looksLikeHtml(sections.analysis)
        ? sections.analysis
        : cleanDraftText(sections.analysis),
    },
    {
      id: "rectification",
      type: "RECTIFICATION",
      title: "整改建议",
      content: looksLikeHtml(sections.rectification)
        ? sections.rectification
        : cleanDraftText(sections.rectification),
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

function mapDraft(item: BackendReportDraft): ReportDraft {
  return {
    id: item.id,
    billingPointId: item.billingPointPeriodId,
    billingPointCode: item.billingPointCode,
    billingPointName: item.billingPointName,
    city: { code: item.cityCode, name: item.cityName ?? item.cityCode },
    period: item.period,
    overLimitType: overLimitTypeLabel(item.overLimitType),
    maxExceedRatio: item.maxExceedRatio,
    status:
      item.status === "GENERATING"
        ? "GENERATING"
        : item.status === "FORMALIZED"
          ? "FINALIZED"
          : "EDITING",
    blocks: sectionsToBlocks(item.sections),
    imageFileIds: item.currentImageFileIds ?? [],
    messages: (item.messages ?? []).flatMap((message) => [
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
    updatedAt: item.updatedAt,
    formalReportId: item.formalReportId,
    entityVersion: item.version,
  };
}

function mapReport(item: BackendReportSummary): ReportSummary {
  const sectionParts = item.sections
    ? [
        item.sections.situation,
        item.sections.analysis,
        item.sections.rectification,
      ]
        .map((part) => cleanReportPart(part))
        .filter(
          (part) =>
            part.length > 0 &&
            part !== "Historical Word preview" &&
            part !== "Original Word file is the source of truth.",
        )
    : [];
  const summary =
    sectionParts.length > 0
      ? sectionParts.join("\n")
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
    source:
      item.sourceType === "HISTORICAL" || item.sourceType === "IMPORTED"
        ? "HISTORICAL_IMPORT"
        : "SYSTEM",
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
    previewHtml: buildReportPreviewHtml(item.sections),
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

function cleanDraftText(value: string | null | undefined): string {
  if (value === null || value === undefined) return "";
  return value
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|section|article|h[1-6]|li|tr)>/gi, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&nbsp;/g, " ")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function cleanReportPart(value: string | null | undefined): string {
  if (value === null || value === undefined) return "";
  return value
    .trim()
    .replace(/Historical Word preview/gi, "")
    .replace(/Original Word file is the source of truth\./gi, "")
    .trim();
}

function looksLikeHtml(value: string): boolean {
  return /<\/?(div|p|table|tr|td|th|figure|img|section|article|h[1-6]|ul|ol|li)\b/i.test(
    value,
  );
}

function mapHistoricalCandidate(
  item: BackendHistoricalReportCandidate,
): HistoricalReportCandidate {
  return {
    billingPointPeriodId: item.billingPointPeriodId,
    billingPointCode: item.billingPointCode,
    billingPointName: item.billingPointName,
    cityCode: item.cityCode,
    cityName: item.cityName,
    period: item.period,
    overLimitType: overLimitTypeLabel(item.overLimitType),
    maxRatio: item.maxRatio ?? null,
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
      throw new Error(taskErrorMessage(task.errorCode));
    }
    await new Promise((resolve) => globalThis.setTimeout(resolve, 1_000));
  }
  throw new Error("正式报告生成超时，请稍后在历史报告中查看任务结果");
}

function taskErrorMessage(errorCode: string | null): string {
  if (errorCode === null) return "正式报告生成失败";
  return TASK_ERROR_MESSAGES[errorCode] ?? errorCode;
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
          await httpClient.get(
            `/api/v1/dashboard/summary${query ? `?${query}` : ""}`,
          ),
        ),
      );
    },
  },

  imports: {
    /**
     * 保留原 list()，避免影响项目里已经存在的调用方。
     * 默认读取第一页，每页100条。
     */
    async list(): Promise<ImportBatch[]> {
      const response = asResult<PageResult<ImportBatch>>(
        await httpClient.get("/api/v1/import-batches?page=0&size=100"),
      );
      return response.items;
    },

    /**
     * 分页查询导入批次。
     * 后端单页最大100条，所以前端也把单页大小限制在100以内。
     * 注意：这是“每页100条”，不是“总共只能100条”。
     */
    async listPage(
      page = 0,
      size = 100,
      datasetType?: DatasetType,
    ): Promise<PageResult<ImportBatch>> {
      const safePage = Math.max(0, Math.trunc(page));
      const safeSize = Math.min(100, Math.max(1, Math.trunc(size)));
      const query = queryString({
        datasetType: datasetType ?? "",
        page: safePage,
        size: safeSize,
      });

      return asResult<PageResult<ImportBatch>>(
        await httpClient.get(`/api/v1/import-batches?${query}`),
      );
    },

    /**
     * 获取“本次导入”对应的全部批次。
     *
     * 例如：
     * - 88个批次：通常1页即可找到；
     * - 180个批次：自动翻页；
     * - 350个批次：继续翻页；
     *
     * 找齐本次传入的批次ID后立即停止，不会把全部历史批次无上限拉回浏览器。
     */
    async listTracked(
      ids: string[],
      datasetType?: DatasetType,
    ): Promise<ImportBatch[]> {
      const orderedIds = [
        ...new Set(
          ids.filter(
            (id) =>
              id !== null &&
              id !== undefined &&
              id.trim().length > 0,
          ),
        ),
      ];

      if (orderedIds.length === 0) {
        return [];
      }

      const targetIds = new Set(orderedIds);
      const found = new Map<string, ImportBatch>();
      let page = 0;

      while (true) {
        const result = await businessApi.imports.listPage(
          page,
          100,
          datasetType,
        );

        for (const batch of result.items ?? []) {
          if (!targetIds.has(batch.id)) {
            continue;
          }
          found.set(batch.id, batch);
        }

        if (found.size === targetIds.size) {
          break;
        }

        if (
          result.totalPages <= 0 ||
          (result.items ?? []).length === 0 ||
          page + 1 >= result.totalPages
        ) {
          break;
        }

        page += 1;
      }

      return orderedIds
        .map((id) => found.get(id))
        .filter(
          (batch): batch is ImportBatch => batch !== undefined,
        );
    },

    async get(id: string): Promise<ImportBatch | undefined> {
      return asResult<ImportBatch>(
        await httpClient.get(
          `/api/v1/import-batches/${encodeURIComponent(id)}`,
        ),
      );
    },

    async create(input: CreateImportInput, file: File): Promise<ImportBatch[]> {
      const form = new FormData();
      form.set("datasetType", input.datasetType);
      if (input.period !== undefined && input.period.length > 0) {
        form.set("period", input.period);
      }
      form.set("file", file);

      const response = await httpClient.postForm(
        "/api/v1/import-batches",
        form,
        LONG_RUNNING_REQUEST,
      );

      const result = asResult<
        ImportBatch | { items?: ImportBatch[]; batches?: ImportBatch[] }
      >(response);

      if ("batches" in result && Array.isArray(result.batches)) {
        return result.batches;
      }
      if ("items" in result && Array.isArray(result.items)) {
        return result.items;
      }
      return [result as ImportBatch];
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
        await httpClient.post("/api/v1/export-jobs", input, {
          headers: { "Idempotency-Key": crypto.randomUUID() },
        }),
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
      return httpClient.getBlob(job.downloadUrl, LONG_RUNNING_REQUEST);
    },
  },

  billingPoints: {
    async list(
      query: BillingPointQuery,
    ): Promise<PageResult<BillingPointDetail["summary"]>> {
      /**
       * 前端报告状态与后端报告状态并不完全一致：
       *
       * 前端：
       * NONE      未生成
       * DRAFT     待生成
       * FINAL     已生成
       * CORRECTED 已更正
       *
       * 后端报账点列表：
       * NONE
       * PENDING
       * GENERATED
       *
       * 因此在请求后端之前统一转换。
       */
      const backendReportStatus =
        query.reportStatus === "DRAFT"
          ? "PENDING"
          : query.reportStatus === "FINAL" ||
          query.reportStatus === "CORRECTED"
            ? "GENERATED"
            : query.reportStatus === "NONE"
              ? "NONE"
              : "";

      /**
       * 所有查询条件都必须交给后端。
       *
       * 不能再：
       * 1. 后端先分页；
       * 2. 前端再对当前页 result.items 过滤。
       *
       * 否则会造成：
       * - 列表只有几条；
       * - totalElements 仍然是查询前数量；
       * - totalPages 仍然是查询前页数；
       * - 后面的分页全部为空。
       *
       * 正确流程：
       * 后端全量过滤
       * → 计算 totalElements
       * → 计算 totalPages
       * → 最后分页
       */
      const params = queryString({
        code: query.code,
        name: query.name,
        cityCode: query.cityCode,
        district: query.district,
        period: query.period,

        paymentEligible:
          query.paymentEligible === undefined
            ? ""
            : query.paymentEligible
              ? "true"
              : "false",

        billingPointStatus: query.billingPointStatus,
        auditStatus: query.auditStatus,
        reportStatus: backendReportStatus,

        focusPeriod: query.focusPeriod,
        focusCityCode: query.focusCityCode,

        page: Math.max(0, query.page - 1),
        size: query.size,
      });

      const result = asResult<
        BackendPage<BackendBillingPointSummary>
      >(
        await httpClient.get(
          `/api/v1/billing-point-periods?${params}`,
        ),
      );

      return {
        items: (result.items ?? []).map(mapBillingSummary),

        // 后端页码从0开始，前端从1开始。
        page: result.page + 1,

        size: result.size,

        /**
         * 总条数必须完全使用后端过滤后的结果。
         * 禁止使用：
         *
         * result.items.length
         *
         * 因为那只能表示“当前页有多少条”，
         * 不能表示整个查询结果有多少条。
         */
        totalElements:
          result.totalElements ??
          result.total ??
          0,

        /**
         * 总页数同样必须使用后端过滤后的 totalPages。
         */
        totalPages: result.totalPages,
      };
    },

    async get(
      id: string,
    ): Promise<BillingPointDetail | undefined> {
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
      return mapDraft(raw);
    },

    async createCorrection(
      reportId: string,
      reason: string,
    ): Promise<ReportDraft> {
      const raw = asResult<BackendReportDraft>(
        await httpClient.post(
          `/api/v1/report-drafts/corrections/${encodeURIComponent(reportId)}`,
          { reason },
        ),
      );
      return mapDraft(raw);
    },

    async get(id: string): Promise<ReportDraft | undefined> {
      const raw = asResult<BackendReportDraft>(
        await httpClient.get(`/api/v1/report-drafts/${encodeURIComponent(id)}`),
      );
      return mapDraft(raw);
    },

    async save(id: string, draft: ReportDraft): Promise<ReportDraft> {
      const raw = asResult<BackendReportDraft>(
        await httpClient.patch(
          `/api/v1/report-drafts/${encodeURIComponent(id)}`,
          blocksToSections(draft.blocks),
          { headers: { "If-Match": String(draft.entityVersion) } },
        ),
      );
      return mapDraft(raw);
    },

    async uploadImage(
      id: string,
      file: File,
    ): Promise<{ fileId: string; entityVersion: number }> {
      const form = new FormData();
      form.set("file", file);
      const response = asResult<{ fileId: string; entityVersion: number }>(
        await httpClient.postForm(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/images`,
          form,
        ),
      );
      return response;
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
          {
            headers: { "If-Match": String(version ?? 0) },
            timeoutMs: 600_000,
          },
        ),
      );
      return mapDraft(raw);
    },

    async removeImage(
      id: string,
      fileId: string,
      version: number,
    ): Promise<ReportDraft> {
      const raw = asResult<BackendReportDraft>(
        await httpClient.delete(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/images/${encodeURIComponent(fileId)}`,
          { headers: { "If-Match": String(version) } },
        ),
      );
      return mapDraft(raw);
    },

    async reorderImages(
      id: string,
      imageFileIds: string[],
      version: number,
    ): Promise<ReportDraft> {
      const raw = asResult<BackendReportDraft>(
        await httpClient.put(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/images/order`,
          { imageFileIds },
          { headers: { "If-Match": String(version) } },
        ),
      );
      return mapDraft(raw);
    },

    async generate(id: string, version: number): Promise<ReportSummary> {
      const task = asResult<{ taskId: string }>(
        await httpClient.post(
          `/api/v1/report-drafts/${encodeURIComponent(id)}/formal-report`,
          {},
          { headers: { "If-Match": String(version) } },
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
        items: (result.items ?? []).map(mapReport),
        page: result.page + 1,
        size: result.size,
        totalElements: result.totalElements ?? result.total ?? 0,
        totalPages: result.totalPages,
      };
    },

    async get(id: string): Promise<ReportSummary | undefined> {
      return fetchReportById(id);
    },

    async listHistoricalCandidates(query: {
      cityCode: string;
      keyword: string;
    }): Promise<HistoricalReportCandidate[]> {
      const params = queryString({
        cityCode: query.cityCode,
        keyword: query.keyword,
      });
      return asResult<BackendHistoricalReportCandidate[]>(
        await httpClient.get(
          `/api/v1/historical-report-candidates${params ? `?${params}` : ""}`,
        ),
      ).map(mapHistoricalCandidate);
    },

    async listHistoricalBillingPoints(query: {
      cityCode: string;
      keyword: string;
    }): Promise<HistoricalReportBillingPoint[]> {
      const params = queryString({
        cityCode: query.cityCode,
        keyword: query.keyword,
      });
      return asResult<HistoricalReportBillingPoint[]>(
        await httpClient.get(
          `/api/v1/historical-report-billing-points${params ? `?${params}` : ""}`,
        ),
      );
    },

    async listHistoricalPeriods(query: {
      billingPointCode: string;
      cityCode: string;
    }): Promise<HistoricalReportPeriod[]> {
      const params = queryString({
        cityCode: query.cityCode,
      });
      return asResult<HistoricalReportPeriod[]>(
        await httpClient.get(
          `/api/v1/historical-report-billing-points/${encodeURIComponent(query.billingPointCode)}/periods${
            params ? `?${params}` : ""
          }`,
        ),
      );
    },

    async importHistorical(input: {
      billingPointPeriodId?: string;
      billingPointCode?: string;
      cityCode?: string;
      period?: string;
      file: File;
    }): Promise<ReportSummary> {
      const form = new FormData();
      if (input.billingPointPeriodId) {
        form.set("billingPointPeriodId", input.billingPointPeriodId);
      }
      if (input.billingPointCode) {
        form.set("billingPointCode", input.billingPointCode);
      }
      if (input.cityCode) form.set("cityCode", input.cityCode);
      if (input.period) form.set("period", input.period);
      form.set("file", input.file);

      const created = asResult<{
        reportId?: string;
        reportPublicId?: string;
        taskId?: string;
      }>(await httpClient.postForm("/api/v1/historical-report-imports", form));

      const reportId = created.reportId ?? created.reportPublicId;
      if (reportId !== undefined) {
        return fetchReportById(reportId);
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

    async wordBlob(id: string): Promise<Blob> {
      return httpClient.getBlob(
        `/api/v1/reports/${encodeURIComponent(id)}/word?inline=true`,
      );
    },
  },

  reportGeneration: {
    async candidates(
      cityCode?: string,
    ): Promise<ReportGenerationInitialContent["candidate"][]> {
      const params = queryString({ cityCode: cityCode ?? "" });
      return asResult<ReportGenerationInitialContent["candidate"][]>(
        await httpClient.get(
          `/api/v1/report-generation/candidates${params ? `?${params}` : ""}`,
        ),
      );
    },

    async initialContent(
      billingPointCode: string,
      period: string,
    ): Promise<ReportGenerationInitialContent> {
      const params = queryString({ billingPointCode, period });
      return asResult<ReportGenerationInitialContent>(
        await httpClient.get(
          `/api/v1/report-generation/initial-content?${params}`,
        ),
      );
    },

    async correctionInitialContent(
      reportId: string,
    ): Promise<ReportGenerationInitialContent> {
      return asResult<ReportGenerationInitialContent>(
        await httpClient.get(
          `/api/v1/report-generation/corrections/${encodeURIComponent(reportId)}/initial-content`,
        ),
      );
    },

    async analyzeImages(
      input: ReportGenerationImageAnalysisInput,
    ): Promise<ReportGenerationImageAnalysisResult> {
      return asResult<ReportGenerationImageAnalysisResult>(
        await httpClient.post("/api/v1/report-generation/image-analysis", input),
      );
    },

    async generate(input: {
      billingPointCode: string;
      period: string;
      contentHtml: string;
    }): Promise<ReportSummary> {
      const created = asResult<{ reportId: string; reportNumber: string }>(
        await httpClient.post(
          "/api/v1/report-generation/formal-reports",
          input,
          {
            headers: { "Idempotency-Key": crypto.randomUUID() },
          },
        ),
      );
      return fetchReportById(created.reportId);
    },

    async regenerate(
      reportId: string,
      input: {
        billingPointCode: string;
        period: string;
        contentHtml: string;
        reason: string;
      },
    ): Promise<ReportSummary> {
      const updated = asResult<{ reportId: string; reportNumber: string }>(
        await httpClient.put(
          `/api/v1/report-generation/formal-reports/${encodeURIComponent(reportId)}`,
          input,
        ),
      );
      return fetchReportById(updated.reportId);
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
      enabled?: boolean;
      initialPassword?: string;
      confirmPassword?: string;
    }): Promise<ManagedUser> {
      return asResult<ManagedUser>(
        await httpClient.post("/api/v1/users", input),
      );
    },

    async update(
      id: string,
      input:
        | string
        | {
            displayName: string;
            cityCode?: string;
            enabled?: boolean;
            version?: number;
          },
    ): Promise<ManagedUser> {
      const body = typeof input === "string" ? { displayName: input } : input;
      return asResult<ManagedUser>(
        await httpClient.patch(`/api/v1/users/${encodeURIComponent(id)}`, body),
      );
    },

    async resetPassword(
      id: string,
      newPassword?: string,
      confirmPassword?: string,
    ): Promise<ManagedUser> {
      return asResult<ManagedUser>(
        await httpClient.post(
          `/api/v1/users/${encodeURIComponent(id)}/password-resets`,
          {
            ...(newPassword === undefined ? {} : { newPassword }),
            ...(confirmPassword === undefined ? {} : { confirmPassword }),
          },
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
      confirmPassword?: string,
    ): Promise<void> {
      await httpClient.patch("/api/v1/users/current/password", {
        currentPassword,
        newPassword,
        ...(confirmPassword === undefined ? {} : { confirmPassword }),
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

export function buildReportPreviewHtml(
  sections:
    | {
        title: string;
        situation: string;
        analysis: string;
        rectification: string;
      }
    | null
    | undefined,
): string | null {
  if (sections === null || sections === undefined) return null;
  const situation = cleanReportPart(sections.situation);
  const analysis = cleanReportPart(sections.analysis);
  const rectification = cleanReportPart(sections.rectification);
  if (
    looksLikeHtml(situation) &&
    analysis.length === 0 &&
    rectification.length === 0
  ) {
    return situation;
  }
  const body = [
    ["一、情况说明", situation],
    ["二、排查分析", analysis],
    ["三、整改小结", rectification],
  ]
    .map(
      ([heading, content]) =>
        `<section><h2>${heading}</h2>${reportPartHtml(content ?? "")}</section>`,
    )
    .join("");
  return `<article class="confirmed-report-content"><h1>${escapeReportHtml(sections.title)}</h1>${body}</article>`;
}

function reportPartHtml(value: string): string {
  if (looksLikeHtml(value)) return value;
  return `<p>${escapeReportHtml(value).replace(/\r?\n/g, "<br>")}</p>`;
}

function escapeReportHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

export async function triggerBrowserDownload(
  url: string,
  fileName = "download",
): Promise<void> {
  const response = await httpClient.getBlobResponse(url);
  saveBlob(response.blob, response.fileName ?? fileName);
}

export function formatPercent(
  value: string | number | null | undefined,
): string {
  if (value === null || value === undefined || value === "") return "-";
  const text = String(value).trim();
  if (text === "" || text === "-") return "-";
  const raw = text.endsWith("%") ? text.slice(0, -1) : text;
  const numeric = Number(raw);
  if (!Number.isFinite(numeric)) return text;
  return `${numeric.toFixed(2)}%`;
}
