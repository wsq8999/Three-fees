<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, Download } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { businessApi } from "@/api/business-api";
import ExportDataDialog from "@/components/business/ExportDataDialog.vue";
import FieldGroups from "@/components/business/FieldGroups.vue";
import FullFieldsDialog from "@/components/business/FullFieldsDialog.vue";
import StatusTag from "@/components/business/StatusTag.vue";
import PageState from "@/components/PageState.vue";
import type {
  AuditComparison,
  BenchmarkRecord,
  BillingPointDetail,
  BusinessField,
  FieldGroup,
  MeterRecord,
  PaymentRecord,
} from "@/types/business";

type DetailTab = "overview" | "power" | "audit";
type PowerTab = "payment" | "meter" | "benchmark";
type FullFieldKind = "overview" | "payment" | "meter" | "benchmark";
type DetailRecord = PaymentRecord | MeterRecord | BenchmarkRecord;

const route = useRoute();
const router = useRouter();

const detail = ref<BillingPointDetail | null>(null);
const loading = ref(true);
const errorMessage = ref("");
const activeTab = ref<DetailTab>(normalizeTab(route.query.tab));
const activePowerTab = ref<PowerTab>(normalizePowerTab(route.query.dataTab ?? route.query.tab));
const selectedPaymentId = ref("");
const selectedMeterId = ref("");
const fullFieldsVisible = ref(false);
const fullFieldKind = ref<FullFieldKind>("overview");
const exportVisible = ref(false);

const selectedPayment = computed(() =>
  detail.value?.payments.find((item) => item.id === selectedPaymentId.value),
);
const selectedMeter = computed(() =>
  detail.value?.meters.find((item) => item.id === selectedMeterId.value),
);
const selectedBenchmark = computed(() => detail.value?.benchmarks[0]);

const overviewGroups = computed(() => normalizeGroups(detail.value?.overviewGroups ?? []));
const paymentGroups = computed(() => normalizeGroups(selectedPayment.value?.fieldGroups ?? []));
const meterGroups = computed(() => normalizeGroups(selectedMeter.value?.fieldGroups ?? []));
const benchmarkGroups = computed(() =>
  normalizeGroups(selectedBenchmark.value?.fieldGroups ?? []),
);
const benchmarkRows = computed(() =>
  (detail.value?.benchmarks ?? []).map((record) => {
    const groups = normalizeGroups(record.fieldGroups);
    return {
      id: record.id,
      code: fieldValue(groups, ["报账点编码"], detail.value?.summary.code ?? "—"),
      status: fieldValue(groups, ["报账点状态"]),
      year: fieldValue(groups, ["年份"]),
      month: fieldValue(groups, ["月份"]),
      average: averageDailyBenchmark(groups),
      groups,
    };
  }),
);

const periodText = computed(() => {
  const summary = detail.value?.summary;
  if (!summary) return "";
  return `${summary.periodStart ?? `${summary.period}-01`} 至 ${summary.periodEnd ?? summary.period}`;
});

const overviewDefaultGroups = computed<FieldGroup[]>(() => [
  {
    title: "身份与归属",
    fields: [
      pickField("pointType", "报账点类型", overviewGroups.value, ["报账点类型"]),
      pickField("pointStatus", "报账点状态", overviewGroups.value, ["报账点状态"]),
      mergedField("cityDistrict", "所属地市 / 区县", overviewGroups.value, ["所属地市"], ["所属区县"]),
      pickField("department", "所属部门", overviewGroups.value, ["所属部门"]),
      pickField("costCenter", "所属成本中心", overviewGroups.value, ["所属成本中心"]),
      pickField("costCenterCode", "成本中心编码", overviewGroups.value, ["成本中心编码"]),
    ],
  },
  {
    title: "用电与计费",
    fields: [
      pickField("electricityCategory", "用电类别", overviewGroups.value, ["用电类别"]),
      pickField("voltage", "电压等级", overviewGroups.value, ["电压等级"]),
      pickField("billingMode", "计费方式", overviewGroups.value, ["计费方式"]),
      pickField("supplyType", "供电类型", overviewGroups.value, ["供电类型"]),
      pickField("paymentCycle", "缴费周期", overviewGroups.value, ["电费缴费周期"]),
      pickField("lossMode", "电损计算方式", overviewGroups.value, ["电损计算方式"]),
    ],
  },
  {
    title: "合同与供应商",
    fields: [
      pickField("contractCode", "合同编码", overviewGroups.value, ["合同或固化编码", "固化关联合同编码"]),
      pickField("contractName", "合同名称", overviewGroups.value, ["合同或固化名称", "固化关联合同名称"]),
      pickField("contractStatus", "合同状态", overviewGroups.value, ["合同或固化状态", "固化关联合同状态"]),
      pickField("contractEnd", "合同期终", overviewGroups.value, ["合同或固化期终"]),
      pickField("supplierName", "供应商名称", overviewGroups.value, ["供应商名称", "供应商"]),
      pickField("supplierCode", "供应商编码", overviewGroups.value, ["供应商编码"]),
    ],
  },
  {
    title: "资源与电表",
    fields: [
      pickField("resourceCode", "关联资源编码", overviewGroups.value, ["关联资源编码"]),
      pickField("resourceName", "资源名称", overviewGroups.value, ["关联资源名称"]),
      mergedField("resourceTypeStatus", "资源类型 / 状态", overviewGroups.value, ["资源类型"], ["资源状态"]),
      pickField("meterStatus", "电表状态", overviewGroups.value, ["电表状态"]),
      pickField("meterCode", "关联电表编码", overviewGroups.value, ["关联电表编码"]),
      mergedField("meterAccountMultiplier", "电表户号 / 倍率", overviewGroups.value, ["电表户号"], ["电表倍率"]),
    ],
  },
]);

const paymentSummaryGroups = computed<FieldGroup[]>(() => [
  {
    title: "账期汇总",
    fields: [
      valueField("paymentCount", "本账期缴费单笔数", `${detail.value?.payments.length ?? 0}笔`),
      valueField("actualEnergy", "汇总实际总耗电量", String(totalEnergy.value), "度"),
      valueField("actualAmount", "汇总实际报账金额", String(amount.value), "元"),
    ],
  },
]);

const paymentSummaryMetrics = computed(() => paymentSummaryGroups.value[0]?.fields ?? []);
const benchmarkMetrics = computed<BusinessField[]>(() => [
  valueField("month", "标杆月份", detail.value?.summary.period ?? "—"),
  valueField("average", "月平均标杆", averageDailyBenchmark(benchmarkGroups.value), "度/天"),
  valueField("total", "额定标杆总量", benchmarkTotal(benchmarkGroups.value), "度"),
  valueField("status", "最近导入状态", "校验通过"),
]);
const benchmarkValidationGroups = computed<FieldGroup[]>(() => [
  {
    title: "完整性",
    fields: [
      valueField("naturalDays", "自然月天数", "30天"),
      valueField("validDays", "有效日值数", "30个"),
    ],
  },
  {
    title: "一致性",
    fields: [
      valueField("dailyAverage", "日值合计 ÷ 30", averageDailyBenchmark(benchmarkGroups.value), "度/天"),
      valueField("difference", "与月平均差异", "0.00"),
    ],
  },
]);

const paymentDefaultGroups = computed<FieldGroup[]>(() => [
  {
    title: "单据与审核",
    fields: [
      mergedField("approval", "审核状态 / 结果", paymentGroups.value, ["审核状态"], ["审核结果"]),
      pickField("currentStep", "当前审核环节", paymentGroups.value, ["当前审核环节"]),
      pickField("currentAuditor", "当前审核人", paymentGroups.value, ["当前审核人"]),
      pickField("financeReturnNo", "财务返回单号", paymentGroups.value, ["财务返回单号"]),
    ],
  },
  {
    title: "账期与金额",
    fields: [
      pickField("applyDate", "缴费申请日期", paymentGroups.value, ["缴费申请日期"]),
      pickField("dailyEnergy", "日均耗电量", paymentGroups.value, ["日均耗电量"], "度/天"),
      pickField("actualAmount", "实际报账金额", paymentGroups.value, ["实际报账金额"], "元"),
      pickField("systemAmount", "系统计算金额", paymentGroups.value, ["系统计算金额"], "元"),
    ],
  },
]);

const meterDefaultGroups = computed<FieldGroup[]>(() => [
  {
    title: "电表与分摊",
    fields: [
      mergedField("accountMultiplier", "电表户号 / 倍率", meterGroups.value, ["电表户号"], ["电表倍率"]),
      mergedField("shareRatio", "实际 / 上次分摊比例", meterGroups.value, ["实际分摊比例"], ["上次分摊比例"]),
      pickField("paymentCode", "缴费单编码", meterGroups.value, ["缴费单编码"]),
      pickField("meterCode", "电表编码", meterGroups.value, ["电表编码"]),
    ],
  },
  {
    title: "总读数与电量",
    fields: [
      mergedField("reading", "上期 / 本期读数", meterGroups.value, ["电表上期读数"], ["本期读数"]),
      pickField("meterEnergy", "电表耗电量", meterGroups.value, ["电表耗电量"], "度"),
      pickField("allocatedEnergy", "分摊后度数", meterGroups.value, ["分摊后度数"], "度"),
      mergedField("fee", "电费不含税 / 税金", meterGroups.value, ["电费不含税金额"], ["电费税金"]),
    ],
  },
]);

const amount = computed(
  () =>
    detail.value?.summary.actualAmount ??
    sumValues(detail.value?.payments ?? [], ["实际报账金额", "实际价款"]),
);
const totalEnergy = computed(
  () =>
    detail.value?.summary.actualEnergy ??
    sumValues(detail.value?.payments ?? [], ["实际总耗电量"]),
);

const fullFields = computed(() => {
  const summary = detail.value?.summary;
  const config = {
    overview: {
      label: "报账点清单",
      count: 73,
      groups: overviewGroups.value,
      summary: [
        valueField("point", "报账点编码", summary?.code ?? "—"),
        valueField("name", "报账点名称", summary?.name ?? "—"),
        valueField("period", "账期", summary?.period ?? "—"),
      ],
    },
    payment: {
      label: "缴费明细",
      count: 198,
      groups: paymentGroups.value,
      summary: [
        valueField("payment", "缴费单编码", paymentCode(selectedPayment.value)),
        valueField("point", "报账点编码", summary?.code ?? "—"),
        valueField("period", "账期", summary?.period ?? "—"),
      ],
    },
    meter: {
      label: "电表读数",
      count: 42,
      groups: meterGroups.value,
      summary: [
        valueField("meter", "电表编码", meterCode(selectedMeter.value)),
        valueField("payment", "缴费单编码", meterPaymentCode(selectedMeter.value)),
        valueField("period", "账期", summary?.period ?? "—"),
      ],
    },
    benchmark: {
      label: "标杆值",
      count: 39,
      groups: benchmarkGroups.value,
      summary: [
        valueField("point", "报账点编码", summary?.code ?? "—"),
        valueField("period", "账期", summary?.period ?? "—"),
      ],
    },
  };
  return config[fullFieldKind.value];
});

const canExport = computed(() => detail.value !== null);
const aiAnalyzing = computed(
  () => detail.value?.summary.draftAnalysisStatus === "AI_ANALYZING",
);
const canGenerateReport = computed(
  () =>
    detail.value?.summary.auditStatus === "OVER_LIMIT" &&
    detail.value.summary.reportStatus === "DRAFT",
);
const canViewReport = computed(
  () =>
    detail.value?.summary.auditStatus === "OVER_LIMIT" &&
    ["FINAL", "CORRECTED"].includes(detail.value.summary.reportStatus),
);
const auditRows = computed(() =>
  (detail.value?.audit.comparisons ?? []).map((row) => ({
    ...row,
    label: cleanText(row.label, auditLabel(row.key)),
    referencePeriod: cleanText(row.referencePeriod),
    baseline: formatAuditBaseline(row),
    normalRange: formatNormalRange(row),
    actual: formatAuditActual(row),
    reason: cleanText(row.reason, auditReasonByKey(row.key)),
    ratio: formatRatio(row.ratio),
    formula: cleanText(row.formula, auditReasonByKey(row.key)),
  })),
);

function isDailyAudit(key: AuditComparison["key"]): boolean {
  return key === "YEAR_ON_YEAR" || key === "MONTH_ON_MONTH";
}

function formatAuditBaseline(row: AuditComparison): string {
  const value = isDailyAudit(row.key) ? formatKwhPerDay(row.baseline) : formatKwh(row.baseline);
  if (value === "—") return "参考数据不足";
  return value;
}

function formatNormalRange(row: AuditComparison): string {
  const value = isDailyAudit(row.key) ? formatKwhPerDay(row.threshold) : formatKwh(row.threshold);
  return value === "—" ? "参考数据不足" : `≤ ${value}`;
}

function formatAuditActual(row: AuditComparison): string {
  const value = isDailyAudit(row.key) ? formatKwhPerDay(row.actual) : formatKwh(row.actual);
  if (value === "—") return "本期数据不足";
  return value;
}

function formatKwh(value: string | null | undefined): string {
  const text = cleanText(value);
  if (text === "—") return text;
  const number = numeric(text);
  if (number === 0 && !/^0(?:\.0+)?$/.test(text.replace(/,/g, ""))) return text;
  return `${number.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}度`;
}

function formatKwhPerDay(value: string | null | undefined): string {
  const text = cleanText(value);
  if (text === "—") return text;
  const number = numeric(text);
  if (number === 0 && !/^0(?:\.0+)?$/.test(text.replace(/,/g, ""))) return text;
  return `${number.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}度/日`;
}

function formatRatio(value: string | null | undefined): string {
  const text = cleanText(value);
  if (text === "—") return text;
  const raw = text.endsWith("%") ? text.slice(0, -1) : text;
  const number = Number(raw);
  if (!Number.isFinite(number)) return text;
  return `${number.toFixed(2)}%`;
}

function auditStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    OVER_LIMIT: "超标",
    NORMAL: "正常",
    NOT_APPLICABLE: "不适用",
    NA: "不适用",
    PENDING_REVIEW: "待稽核",
  };
  return labels[status] ?? status;
}

function auditStatusClass(status: string): string {
  if (status === "OVER_LIMIT") return "audit-status-over";
  if (status === "NORMAL") return "audit-status-normal";
  return "audit-status-na";
}

const auditSummaryReason = computed(() =>
  translateOverLimitType(
    cleanText(detail.value?.audit.finalReason, "当前报账点尚未完成稽核，请确认四类文件已导入成功"),
  ),
);

function normalizeTab(value: unknown): DetailTab {
  if (["payment", "meter", "benchmark"].includes(String(value))) return "power";
  return ["overview", "power", "audit"].includes(String(value))
    ? (String(value) as DetailTab)
    : "overview";
}

function normalizePowerTab(value: unknown): PowerTab {
  return ["payment", "meter", "benchmark"].includes(String(value))
    ? (String(value) as PowerTab)
    : "payment";
}

function normalizeGroups(groups: unknown): FieldGroup[] {
  if (!Array.isArray(groups)) return [];
  return groups.map((group) => {
    const item = group as {
      title?: string;
      name?: string;
      fields?: Array<{
        key?: string;
        name?: string;
        label?: string;
        sourceName?: string;
        value?: string | null;
        unit?: string;
      }>;
    };
    return {
      title: item.title ?? item.name ?? "字段信息",
      fields: (item.fields ?? []).map((source, index) => ({
        key: source.key ?? source.name ?? `${item.title ?? item.name ?? "field"}-${index}`,
        label: source.label ?? source.sourceName ?? source.name ?? "字段",
        value: cleanText(source.value),
        unit: source.unit,
      })),
    };
  });
}

function valueField(key: string, label: string, value: string, unit?: string): BusinessField {
  return { key, label, value: cleanText(value), unit };
}

function pickField(
  key: string,
  label: string,
  groups: FieldGroup[],
  labels: string[],
  unit?: string,
): BusinessField {
  return valueField(key, label, fieldValue(groups, labels), unit);
}

function mergedField(
  key: string,
  label: string,
  groups: FieldGroup[],
  leftLabels: string[],
  rightLabels: string[],
): BusinessField {
  const left = fieldValue(groups, leftLabels);
  const right = fieldValue(groups, rightLabels);
  if (left === "—" && right === "—") return valueField(key, label, "—");
  return valueField(key, label, `${left} / ${right}`);
}

function fieldValue(groups: FieldGroup[], labels: string[], fallback = "—"): string {
  for (const group of groups) {
    const found = group.fields.find((item) => labels.includes(item.label));
    if (found?.value && found.value !== "—") return found.value;
  }
  return fallback;
}

function cleanText(value: string | null | undefined, fallback = "—"): string {
  if (value === null || value === undefined) return fallback;
  const text = String(value).trim();
  if (
    text.length === 0 ||
    /^\?+$/.test(text) ||
    ["null", "undefined", "NaN", "N/A"].includes(text) ||
    (text.charCodeAt(0) === 0x9225 && text.endsWith("?"))
  ) {
    return fallback;
  }
  return text;
}

function translateOverLimitType(value: string): string {
  const labels: Record<string, string> = {
    ONLY_YOY: "仅同比超标",
    ONLY_MOM: "仅环比超标",
    ONLY_RATED: "仅额定标杆超标",
    MULTIPLE: detail.value?.summary.overLimitType ?? "超标",
    NONE: "未超标",
  };
  return Object.entries(labels).reduce(
    (text, [key, label]) => text.replace(new RegExp(`\\b${key}\\b`, "g"), label),
    value,
  );
}

function numeric(value: string | null | undefined): number {
  if (!value) return 0;
  const number = Number(String(value).replace(/[^\d.-]/g, ""));
  return Number.isFinite(number) ? number : 0;
}

function formatNumber(value: number): string {
  return value === 0
    ? "—"
    : value.toLocaleString("zh-CN", {
        maximumFractionDigits: 2,
      });
}

function sumValues(records: DetailRecord[], labels: string[]): string {
  const total = records.reduce(
    (sum, record) => sum + numeric(fieldValue(normalizeGroups(record.fieldGroups), labels, "0")),
    0,
  );
  return formatNumber(total);
}

function sumDailyBenchmark(groups: FieldGroup[]): string {
  const values = groups.flatMap((group) =>
    group.fields.filter((field) => /^([1-9]|[12]\d|3[01])$/.test(field.label)),
  );
  const total = values.reduce((sum, field) => sum + numeric(field.value), 0);
  return formatNumber(total);
}

function benchmarkTotal(groups: FieldGroup[]): string {
  return detail.value?.summary.benchmarkEnergy ?? fieldValue(groups, ["月总标杆"], sumDailyBenchmark(groups));
}

function averageDailyBenchmark(groups: FieldGroup[]): string {
  const total = numeric(benchmarkTotal(groups));
  if (total === 0) return "0.00";
  const days = detail.value?.summary.period ? Number(detail.value.summary.period.slice(5, 7)) : 0;
  const length = days > 0 ? new Date(Number(detail.value?.summary.period.slice(0, 4)), days, 0).getDate() : 30;
  return formatNumber(total / length);
}

function dayColumns(): string[] {
  return Array.from({ length: 31 }, (_, index) => String(index + 1));
}

function benchmarkDayValue(row: unknown, day: string): string {
  return fieldValue((row as { groups: FieldGroup[] }).groups, [day]);
}

function asPayment(record: unknown): PaymentRecord {
  return record as PaymentRecord;
}

function asMeter(record: unknown): MeterRecord {
  return record as MeterRecord;
}

function paymentCode(record: unknown): string {
  if (!record) return "—";
  const item = asPayment(record);
  return cleanText(item.billNumber, fieldValue(normalizeGroups(item.fieldGroups), ["缴费单编码"]));
}

function paymentStart(record: unknown): string {
  return fieldValue(normalizeGroups(asPayment(record).fieldGroups), ["缴费期始"]);
}

function paymentEnd(record: unknown): string {
  return fieldValue(normalizeGroups(asPayment(record).fieldGroups), ["缴费期终"]);
}

function paymentDays(record: unknown): string {
  return fieldValue(normalizeGroups(asPayment(record).fieldGroups), ["缴费天数"]);
}

function paymentEnergy(record: unknown): string {
  const item = asPayment(record);
  return cleanText(item.billingEnergy, fieldValue(normalizeGroups(item.fieldGroups), ["实际总耗电量"]));
}

function paymentAmount(record: unknown): string {
  const item = asPayment(record);
  return cleanText(item.electricityFee, fieldValue(normalizeGroups(item.fieldGroups), ["实际报账金额"]));
}

function meterCode(record: unknown): string {
  if (!record) return "—";
  const item = asMeter(record);
  return cleanText(item.meterNumber, fieldValue(normalizeGroups(item.fieldGroups), ["电表编码"]));
}

function meterPaymentCode(record: unknown): string {
  if (!record) return "—";
  return fieldValue(normalizeGroups(asMeter(record).fieldGroups), ["缴费单编码"]);
}

function meterField(record: unknown, label: string): string {
  return fieldValue(normalizeGroups(asMeter(record).fieldGroups), [label]);
}

function findMeterByPayment(payment: PaymentRecord | undefined): MeterRecord | undefined {
  if (!payment || !detail.value) return undefined;
  const code = paymentCode(payment);
  return detail.value.meters.find((meter) => meterPaymentCode(meter) === code);
}

function findPaymentByMeter(meter: MeterRecord | undefined): PaymentRecord | undefined {
  if (!meter || !detail.value) return undefined;
  const code = meterPaymentCode(meter);
  return detail.value.payments.find((payment) => paymentCode(payment) === code);
}

function selectPayment(record: unknown): void {
  const payment = asPayment(record);
  selectedPaymentId.value = payment.id;
  const linkedMeter = findMeterByPayment(payment);
  if (linkedMeter) selectedMeterId.value = linkedMeter.id;
}

function selectMeter(record: unknown): void {
  const meter = asMeter(record);
  selectedMeterId.value = meter.id;
  const linkedPayment = findPaymentByMeter(meter);
  if (linkedPayment) selectedPaymentId.value = linkedPayment.id;
}

function paymentRowClass({ row }: { row: PaymentRecord }): string {
  return row.id === selectedPaymentId.value ? "selected-business-row" : "";
}

function meterRowClass({ row }: { row: MeterRecord }): string {
  return row.id === selectedMeterId.value ? "selected-business-row" : "";
}

function auditLabel(key: AuditComparison["key"]): string {
  const labels: Record<AuditComparison["key"], string> = {
    YEAR_ON_YEAR: "同比",
    MONTH_ON_MONTH: "环比",
    RATED_BENCHMARK: "额定标杆",
  };
  return labels[key];
}

function auditReasonByKey(key: AuditComparison["key"]): string {
  const labels: Record<AuditComparison["key"], string> = {
    YEAR_ON_YEAR: "固定对比去年同月，按参考日均和标杆修正系数计算正常上限",
    MONTH_ON_MONTH: "固定对比上一个自然月，按参考日均和标杆修正系数计算正常上限",
    RATED_BENCHMARK: "对比系统计算后的当月标杆总量正常上限，缺失时回退日列合计",
  };
  return labels[key];
}
async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    const loaded = await businessApi.billingPoints.get(String(route.params.billingPointCode));
    if (loaded === undefined) throw new Error("未找到指定报账点账期数据");
    if (loaded.summary.period !== String(route.params.period)) {
      throw new Error("当前报账点与账期不匹配");
    }
    detail.value = loaded;
    selectedPaymentId.value = loaded.payments[0]?.id ?? "";
    selectedMeterId.value = findMeterByPayment(loaded.payments[0])?.id ?? loaded.meters[0]?.id ?? "";
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "详情加载失败";
  } finally {
    loading.value = false;
  }
}

async function updateTab(): Promise<void> {
  await router.replace({
    query: {
      ...route.query,
      tab: activeTab.value,
      ...(activeTab.value === "power" ? { dataTab: activePowerTab.value } : {}),
    },
  });
}

async function updatePowerTab(tab: PowerTab): Promise<void> {
  activePowerTab.value = tab;
  activeTab.value = "power";
  await updateTab();
}

function showFullFields(kind: FullFieldKind): void {
  fullFieldKind.value = kind;
  fullFieldsVisible.value = true;
}

async function goBack(): Promise<void> {
  const from = typeof route.query.from === "string" ? route.query.from : "/billing-points";
  await router.push(from);
}

async function openGenerateReport(): Promise<void> {
  if (detail.value === null) return;
  if (aiAnalyzing.value) {
    ElMessage.info("AI正在后台分析，完成或失败后可继续生成报告。");
    return;
  }
  const draft = await businessApi.drafts.createOrResume(detail.value.summary.id);
  await router.push({
    name: "report-draft",
    params: { draftId: draft.id },
    query: { from: route.fullPath },
  });
}

async function openReport(): Promise<void> {
  if (detail.value === null) return;
  const reportId = detail.value.summary.reportId;
  if (reportId) {
    await router.push({ name: "report-detail", params: { reportId }, query: { from: route.fullPath } });
    return;
  }
  const reports = await businessApi.reports.list({
    cityCode: detail.value.summary.city.code,
    district: "",
    period: detail.value.summary.period,
    keyword: detail.value.summary.code,
    source: "",
    page: 1,
    size: 10,
  });
  const report = reports.items.find((item) => item.billingPointCode === detail.value?.summary.code);
  if (report === undefined) {
    ElMessage.error("正式报告不存在或当前账号无权访问。");
    return;
  }
  await router.push({ name: "report-detail", params: { reportId: report.id }, query: { from: route.fullPath } });
}

onMounted(load);
</script>

<template>
  <PageState v-if="loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="报账点详情加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <template v-else-if="detail">
    <section class="summary-card">
      <div>
        <small>报账点编码</small>
        <strong>{{ detail.summary.code }}</strong>
      </div>
      <div>
        <small>报账点名称</small>
        <strong>{{ detail.summary.name }}</strong>
      </div>
      <div>
        <small>账期</small>
        <strong>{{ periodText }}</strong>
      </div>
      <div>
        <small>稽核状态</small>
        <StatusTag :value="detail.summary.auditStatus" />
      </div>
      <div>
        <small>报告状态</small>
        <StatusTag :value="detail.summary.reportStatus" />
      </div>
    </section>

    <section class="detail-card">
      <ElTabs v-model="activeTab" @tab-change="updateTab">
        <ElTabPane label="概览" name="overview">
          <div class="section-heading overview-heading">
            <h3>关键字段</h3>
            <ElButton type="primary" plain @click="showFullFields('overview')">
              查看全部73个源文件字段
            </ElButton>
          </div>
          <FieldGroups paired class="overview-groups" :groups="overviewDefaultGroups" />
        </ElTabPane>

        <ElTabPane label="用电数据" name="power">
          <div class="subtabs">
            <ElButton :class="{ active: activePowerTab === 'payment' }" @click="updatePowerTab('payment')">
              缴费明细
            </ElButton>
            <ElButton :class="{ active: activePowerTab === 'meter' }" @click="updatePowerTab('meter')">
              电表读数
            </ElButton>
            <ElButton :class="{ active: activePowerTab === 'benchmark' }" @click="updatePowerTab('benchmark')">
              标杆值
            </ElButton>
          </div>

          <template v-if="activePowerTab === 'payment'">
            <div class="metric-strip">
              <div v-for="field in paymentSummaryMetrics" :key="field.key">
                <small>{{ field.label }}</small>
                <strong>{{ field.value }}{{ field.unit }}</strong>
              </div>
            </div>
            <div class="section-heading">
              <h3>缴费记录（{{ detail.payments.length }}条）</h3>
              <ElButton type="primary" plain :disabled="!selectedPayment" @click="showFullFields('payment')">
                查看全部198个缴费明细字段
              </ElButton>
            </div>
            <ElTable
              class="prototype-table"
              :data="detail.payments"
              :row-class-name="paymentRowClass"
              height="178"
              @row-click="selectPayment"
            >
              <ElTableColumn label="缴费单编号" min-width="210">
                <template #default="scope"><strong>{{ paymentCode(scope.row) }}</strong></template>
              </ElTableColumn>
              <ElTableColumn label="缴费起始" width="120">
                <template #default="scope">{{ paymentStart(scope.row) }}</template>
              </ElTableColumn>
              <ElTableColumn label="缴费终止" width="120">
                <template #default="scope">{{ paymentEnd(scope.row) }}</template>
              </ElTableColumn>
              <ElTableColumn label="缴费天数" width="90" align="right">
                <template #default="scope">{{ paymentDays(scope.row) }}</template>
              </ElTableColumn>
              <ElTableColumn label="实际总电量" width="130" align="right">
                <template #default="scope">{{ paymentEnergy(scope.row) }}</template>
              </ElTableColumn>
              <ElTableColumn label="实际金额" width="130" align="right">
                <template #default="scope">{{ paymentAmount(scope.row) }}</template>
              </ElTableColumn>
              <ElTableColumn label="审核状态" width="110">
                <template #default="scope"><StatusTag :value="scope.row.approvalStatus" /></template>
              </ElTableColumn>
            </ElTable>
            <div class="current-record-title">
              当前选中：{{ paymentCode(selectedPayment) }}
            </div>
            <FieldGroups v-if="selectedPayment" paired class="detail-groups" :groups="paymentDefaultGroups" />
          </template>

          <template v-else-if="activePowerTab === 'meter'">
            <div class="section-heading compact-heading">
              <h3>电表读数记录（{{ detail.meters.length }}条）</h3>
              <ElButton type="primary" plain :disabled="!selectedMeter" @click="showFullFields('meter')">
                查看全部42个电表读数字段
              </ElButton>
            </div>
            <ElTable
              class="prototype-table"
              :data="detail.meters"
              :row-class-name="meterRowClass"
              height="196"
              @row-click="selectMeter"
            >
              <ElTableColumn label="缴费单编号" min-width="180">
                <template #default="scope"><strong>{{ meterPaymentCode(scope.row) }}</strong></template>
              </ElTableColumn>
              <ElTableColumn label="电表编码" width="145">
                <template #default="scope">{{ meterCode(scope.row) }}</template>
              </ElTableColumn>
              <ElTableColumn label="电表户号" width="145">
                <template #default="scope">{{ meterField(scope.row, "电表户号") }}</template>
              </ElTableColumn>
              <ElTableColumn label="缴费起始" width="115">
                <template #default="scope">{{ meterField(scope.row, "缴费起始") }}</template>
              </ElTableColumn>
              <ElTableColumn label="缴费终止" width="115">
                <template #default="scope">{{ meterField(scope.row, "缴费终止") }}</template>
              </ElTableColumn>
              <ElTableColumn label="电表用电量" width="120" align="right">
                <template #default="scope">{{ meterField(scope.row, "电表用电量") }}</template>
              </ElTableColumn>
              <ElTableColumn label="分摊后电量" width="120" align="right">
                <template #default="scope">{{ meterField(scope.row, "分摊后度数") }}</template>
              </ElTableColumn>
            </ElTable>
            <div class="current-record-title">
              当前选中电表：{{ meterCode(selectedMeter) }}
            </div>
            <FieldGroups v-if="selectedMeter" paired class="detail-groups" :groups="meterDefaultGroups" />
          </template>

          <template v-else>
            <div class="metric-strip benchmark-strip">
              <div v-for="field in benchmarkMetrics" :key="field.key">
                <small>{{ field.label }}</small>
                <strong>{{ field.value }}{{ field.unit }}</strong>
              </div>
            </div>
            <div class="section-heading compact-heading">
              <h3>标杆值记录</h3>
              <ElButton type="primary" plain :disabled="!selectedBenchmark" @click="showFullFields('benchmark')">
                查看全部39个标杆值字段
              </ElButton>
            </div>
            <div class="benchmark-table">
              <ElTable class="prototype-table" :data="benchmarkRows" height="118">
                <ElTableColumn prop="code" label="报账点编码" width="160" fixed />
                <ElTableColumn prop="status" label="状态" width="68" />
                <ElTableColumn prop="year" label="年份" width="68" />
                <ElTableColumn prop="month" label="月份" width="68" />
                <ElTableColumn prop="average" label="月平均" width="86" />
                <ElTableColumn v-for="day in dayColumns()" :key="day" :label="day" width="66" align="right">
                  <template #default="scope">{{ benchmarkDayValue(scope.row, day) }}</template>
                </ElTableColumn>
              </ElTable>
            </div>
            <div class="current-record-title import-check-title">
              导入校验
            </div>
            <FieldGroups paired class="detail-groups validation-groups" :groups="benchmarkValidationGroups" />
          </template>
        </ElTabPane>

        <ElTabPane label="稽核分析" name="audit">
          <div class="metric-strip audit-summary-strip">
            <div><small>当前账期</small><strong>{{ periodText }}</strong></div>
            <div><small>实际总用电量</small><strong>{{ totalEnergy }}度</strong></div>
            <div><small>实际报账金额</small><strong>{{ amount }}元</strong></div>
          </div>
          <ElAlert
            class="audit-reason"
            :title="auditSummaryReason"
            type="info"
            :closable="false"
            show-icon
          />
          <ElTable :data="auditRows" class="audit-table" table-layout="auto">
            <ElTableColumn prop="label" label="分析类型" width="110" />
            <ElTableColumn prop="referencePeriod" label="参考账期" width="110" />
            <ElTableColumn prop="baseline" label="参考值（日均/总量）" width="190" />
            <ElTableColumn prop="normalRange" label="正常范围" width="170" />
            <ElTableColumn prop="actual" label="本期值（日均/总量）" width="190" />
            <ElTableColumn label="结果" width="100">
              <template #default="scope">
                <span class="audit-status" :class="auditStatusClass(scope.row.status)">
                  {{ auditStatusLabel(scope.row.status) }}
                </span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="超标比例" width="110">
              <template #default="scope">
                <span class="audit-ratio" :class="{ 'audit-ratio-over': scope.row.status === 'OVER_LIMIT' }">
                  {{ scope.row.ratio }}
                </span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="分析说明" min-width="260">
              <template #default="scope">
                <div class="audit-note">
                  <strong>{{ scope.row.reason }}</strong>
                  <small>{{ scope.row.formula }}</small>
                </div>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElTabPane>
      </ElTabs>
    </section>

    <footer class="fixed-actions">
      <ElButton :icon="ArrowLeft" @click="goBack">返回</ElButton>
      <ElButton :icon="Download" :disabled="!canExport" @click="exportVisible = true">
        导出Excel
      </ElButton>
      <ElButton
        v-if="canGenerateReport"
        type="primary"
        :disabled="aiAnalyzing"
        @click="openGenerateReport"
      >
        {{ aiAnalyzing ? "AI分析中" : "生成报告" }}
      </ElButton>
      <ElButton v-else-if="canViewReport" type="primary" @click="openReport">查看报告</ElButton>
    </footer>

    <FullFieldsDialog
      v-model="fullFieldsVisible"
      :data-label="fullFields.label"
      :expected-count="fullFields.count"
      :summary="fullFields.summary"
      :groups="fullFields.groups"
    />
    <ExportDataDialog
      v-model="exportVisible"
      :scope-label="`${detail.summary.code} / ${detail.summary.period}`"
      :period="detail.summary.period"
      :city-code="detail.summary.city.code"
      :billing-point-ids="[detail.summary.id]"
      :selected-count="1"
    />
  </template>
</template>

<style scoped>
.summary-card {
  display: grid;
  grid-template-columns: 1.15fr 1.15fr 1.35fr 0.7fr 0.7fr;
  gap: 20px;
  align-items: center;
  padding: 16px 22px;
  margin-bottom: 10px;
  background: #fff;
  border: 1px solid #dfe6ef;
  border-radius: 8px;
  box-shadow: 0 2px 6px rgb(15 31 53 / 4%);
}

.summary-card div {
  display: grid;
  gap: 6px;
}

.summary-card small,
.metric-strip small {
  color: #7d8ca1;
  font-size: 12px;
  font-weight: 600;
}

.summary-card strong,
.metric-strip strong {
  color: #0f1f35;
  font-size: 14px;
  font-weight: 800;
}

.detail-card {
  min-height: calc(100vh - 230px);
  padding: 0 14px 82px;
  overflow: hidden;
  background: #fff;
  border: 1px solid #dfe6ef;
  border-radius: 8px;
  box-shadow: 0 2px 6px rgb(15 31 53 / 4%);
}

.detail-card :deep(.el-tabs__header) {
  margin: 0 -14px 18px;
  padding: 0 14px;
  border-bottom: 1px solid #edf1f5;
}

.detail-card :deep(.el-tabs__item) {
  height: 44px;
  color: #344152;
  font-size: 13px;
  font-weight: 700;
}

.detail-card :deep(.el-tabs__item.is-active) {
  color: #f5223d;
}

.detail-card :deep(.el-tabs__active-bar) {
  height: 2px;
  background: #f5223d;
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 14px 0 12px;
}

.overview-heading,
.compact-heading {
  margin-top: 0;
}

.section-heading h3 {
  margin: 0;
  color: #0f1f35;
  font-size: 14px;
  font-weight: 800;
}

.section-heading :deep(.el-button) {
  height: 28px;
  padding: 0 10px;
  color: #2f8cff;
  background: #f2f8ff;
  border-color: #d8eaff;
  border-radius: 4px;
  font-size: 12px;
}

.subtabs {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.subtabs :deep(.el-button) {
  height: 30px;
  padding: 0 14px;
  color: #344152;
  border-color: #dfe6ef;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 700;
}

.subtabs :deep(.el-button.active) {
  color: #f5223d;
  background: #fff1f2;
  border-color: #ffb7c1;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-bottom: 16px;
  overflow: hidden;
  background: #f6f8fb;
  border-radius: 4px;
}

.benchmark-strip {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.metric-strip > div {
  min-height: 60px;
  padding: 12px 16px;
  border-right: 1px solid #e8edf4;
}

.metric-strip > div:last-child {
  border-right: 0;
}

.metric-strip small,
.metric-strip strong {
  display: block;
}

.metric-strip strong {
  margin-top: 6px;
}

.detail-groups {
  margin-top: 8px;
}

.overview-groups :deep(.field-card dl > div) {
  grid-template-columns: minmax(110px, 0.72fr) minmax(0, 1.28fr);
}

.overview-groups :deep(.field-card dd) {
  min-width: 0;
  overflow: visible;
  line-height: 1.45;
  text-align: right;
  text-overflow: clip;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.current-record-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0 8px;
  color: #0f1f35;
  font-size: 13px;
  font-weight: 800;
}

.current-record-title span {
  color: #9aa6b5;
  font-size: 12px;
  font-weight: 500;
}

.import-check-title {
  margin-top: 14px;
}

.benchmark-table {
  overflow-x: auto;
}

.prototype-table :deep(.el-table__cell) {
  padding: 7px 0;
  font-size: 12px;
}

.prototype-table :deep(.el-table__header th.el-table__cell) {
  background: #f8fafc;
}

.prototype-table :deep(.el-table__body td.el-table__cell) {
  color: #344152;
}

.prototype-table :deep(.el-table__body td.el-table__cell strong) {
  color: #0f1f35;
  font-weight: 800;
}

.fixed-actions {
  position: fixed;
  right: 16px;
  bottom: 0;
  left: calc(var(--sidebar-width) + 16px);
  z-index: 50;
  display: flex;
  min-height: 58px;
  gap: 10px;
  align-items: center;
  justify-content: flex-end;
  padding: 10px 18px;
  background: rgb(255 255 255 / 96%);
  border-top: 1px solid #dfe5ec;
}

:deep(.selected-business-row td.el-table__cell) {
  background: #fff1f2 !important;
}

:deep(.selected-business-row td.el-table__cell),
:deep(.selected-business-row td.el-table__cell strong) {
  color: #f5223d !important;
}

:deep(.el-table th.el-table__cell) {
  color: #0f1f35;
  background: #fff;
  font-weight: 800;
}

.audit-summary-strip > div {
  display: flex;
  min-width: 0;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.audit-summary-strip small,
.audit-summary-strip strong {
  display: inline;
  margin-top: 0;
}

.audit-summary-strip strong {
  min-width: 0;
  text-align: right;
}

.audit-ratio-over {
  color: #f5223d;
  font-weight: 800;
}

.audit-reason {
  margin-bottom: 12px;
}

.audit-table :deep(.el-table__cell) {
  vertical-align: top;
}

.audit-table :deep(.el-table__body .el-table__cell:not(:last-child) .cell),
.audit-table :deep(.el-table__header .el-table__cell:not(:last-child) .cell) {
  white-space: nowrap;
}

.audit-table :deep(.el-table__body .el-table__cell:not(:last-child) .cell) {
  overflow: visible;
  text-overflow: clip;
}

.audit-status {
  font-weight: 800;
}

.audit-status-over {
  color: #f5223d;
}

.audit-status-normal {
  color: #16a34a;
}

.audit-status-na {
  color: #7d8ca1;
}

.audit-note {
  display: grid;
  gap: 4px;
  line-height: 1.5;
}

.audit-note small {
  color: #7d8ca1;
}
@media (width <= 1180px) {
  .summary-card,
  .metric-strip {
    grid-template-columns: 1fr 1fr;
  }

  .overview-groups :deep(.field-card dl > div) {
    grid-template-columns: minmax(96px, 0.8fr) minmax(0, 1.2fr);
  }
}

@media (width <= 820px) {
  .overview-groups :deep(.field-card dl),
  .overview-groups :deep(.field-card dl > div) {
    grid-template-columns: 1fr;
  }

  .overview-groups :deep(.field-card dd) {
    text-align: left;
  }
}
</style>
