import type {
  AuditComparison,
  AuditStatus,
  BenchmarkRule,
  BillingPointDetail,
  BillingPointQuery,
  BusinessCity,
  CreateImportInput,
  DashboardData,
  DatasetType,
  DraftBlock,
  DraftIntent,
  HistoricalReportCandidate,
  ImportBatch,
  ManagedUser,
  PageResult,
  ReportDraft,
  ReportQuery,
  ReportSummary,
  ScenarioSnapshot,
  SendDraftMessageInput,
} from "../types/business";

export const SCENARIO_CITIES: readonly BusinessCity[] = [
  { code: "320100", name: "南京市" },
  { code: "320200", name: "无锡市" },
  { code: "320300", name: "徐州市" },
  { code: "320400", name: "常州市" },
  { code: "320500", name: "苏州市" },
  { code: "320600", name: "南通市" },
  { code: "320700", name: "连云港市" },
  { code: "320800", name: "淮安市" },
  { code: "320900", name: "盐城市" },
  { code: "321000", name: "扬州市" },
  { code: "321100", name: "镇江市" },
  { code: "321200", name: "泰州市" },
  { code: "321300", name: "宿迁市" },
];

export const DATASET_META: Record<
  DatasetType,
  { label: string; fieldCount: number; acceptedFormats: string }
> = {
  BILLING_POINT: {
    label: "报账点清单",
    fieldCount: 73,
    acceptedFormats: ".xlsx / .xls / .csv",
  },
  PAYMENT: {
    label: "缴费明细",
    fieldCount: 198,
    acceptedFormats: ".xlsx / .xls / .csv",
  },
  METER_READING: {
    label: "电表读数",
    fieldCount: 42,
    acceptedFormats: ".xlsx / .xls / .csv",
  },
  BENCHMARK: {
    label: "标杆数据",
    fieldCount: 39,
    acceptedFormats: ".xlsx / .xls / .csv",
  },
};

const DATASET_TYPES = Object.keys(DATASET_META) as DatasetType[];
const ROW_COUNTS: Record<DatasetType, number> = {
  BILLING_POINT: 128,
  PAYMENT: 486,
  METER_READING: 352,
  BENCHMARK: 64,
};
const FIXED_TIME = "2026-08-10T09:30:00+08:00";

function clone<T>(value: T): T {
  return structuredClone(value);
}

function city(code: string): BusinessCity {
  const matched = SCENARIO_CITIES.find((item) => item.code === code);
  if (matched === undefined) throw new Error(`UNKNOWN_CITY:${code}`);
  return clone(matched);
}

export interface AdjustedThresholdInput {
  currentTotal: number | null;
  currentDays: number;
  currentBenchmarkTotal: number | null;
  referenceTotal: number | null;
  referenceDays: number;
  referenceBenchmarkTotal: number | null;
}

export interface AdjustedThresholdResult {
  applicable: boolean;
  currentDaily: number | null;
  referenceDaily: number | null;
  a: number | null;
  b: number | null;
  k: number | null;
  threshold: number | null;
  overLimit: boolean | null;
  ratio: number | null;
}

export function calculateAdjustedThreshold(
  input: AdjustedThresholdInput,
): AdjustedThresholdResult {
  const currentTotal = input.currentTotal;
  const currentBenchmarkTotal = input.currentBenchmarkTotal;
  const referenceTotal = input.referenceTotal;
  const referenceBenchmarkTotal = input.referenceBenchmarkTotal;
  if (
    currentTotal === null ||
    currentBenchmarkTotal === null ||
    referenceTotal === null ||
    referenceBenchmarkTotal === null ||
    input.currentDays <= 0 ||
    input.referenceDays <= 0
  ) {
    return {
      applicable: false,
      currentDaily: null,
      referenceDaily: null,
      a: null,
      b: null,
      k: null,
      threshold: null,
      overLimit: null,
      ratio: null,
    };
  }
  const currentDaily = currentTotal / input.currentDays;
  const referenceDaily = referenceTotal / input.referenceDays;
  const a = currentBenchmarkTotal / input.currentDays;
  const b = referenceBenchmarkTotal / input.referenceDays;
  if (b <= 0) {
    return {
      applicable: false,
      currentDaily,
      referenceDaily,
      a,
      b,
      k: null,
      threshold: null,
      overLimit: null,
      ratio: null,
    };
  }
  const k = Math.max(1, a / b);
  const threshold = referenceDaily * k * 1.2;
  const overLimit = currentDaily > threshold;
  const ratio = threshold > 0 ? (currentDaily - threshold) / threshold : null;
  return {
    applicable: true,
    currentDaily,
    referenceDaily,
    a,
    b,
    k,
    threshold,
    overLimit,
    ratio,
  };
}

function formatNumber(value: number | null, digits = 2): string | null {
  return value === null ? null : value.toFixed(digits);
}

function formatRatio(value: number | null): string | null {
  return value === null ? null : `${(value * 100).toFixed(2)}%`;
}

function comparisons(
  status: AuditStatus,
  actual = "12850.36",
  benchmark = "11500.00",
): AuditComparison[] {
  const overLimit = status === "OVER_LIMIT";
  const notApplicable =
    status === "NOT_APPLICABLE" || status === "PENDING_REVIEW";
  const actualTotal = notApplicable ? null : Number(actual);
  const benchmarkTotal = notApplicable ? null : Number(benchmark);
  const year = calculateAdjustedThreshold({
    currentTotal: actualTotal,
    currentDays: 30,
    currentBenchmarkTotal: benchmarkTotal,
    referenceTotal: overLimit ? 7800 : 12000,
    referenceDays: 30,
    referenceBenchmarkTotal: overLimit ? 10000 : 11000,
  });
  const month = calculateAdjustedThreshold({
    currentTotal: actualTotal,
    currentDays: 30,
    currentBenchmarkTotal: benchmarkTotal,
    referenceTotal: overLimit ? 11000 : 11800,
    referenceDays: 31,
    referenceBenchmarkTotal: 12000,
  });
  const ratedApplicable = actualTotal !== null && benchmarkTotal !== null;
  const ratedOver =
    ratedApplicable && benchmarkTotal === 0
      ? actualTotal > 0
      : ratedApplicable
        ? actualTotal > benchmarkTotal
        : false;
  const ratedRatio =
    ratedApplicable && benchmarkTotal !== 0
      ? (actualTotal - benchmarkTotal) / benchmarkTotal
      : null;
  return [
    {
      key: "YEAR_ON_YEAR",
      label: "同比",
      status: !year.applicable
        ? "NOT_APPLICABLE"
        : year.overLimit
          ? "OVER_LIMIT"
          : "NORMAL",
      referencePeriod: "2025-06",
      baseline: formatNumber(year.referenceDaily),
      threshold: formatNumber(year.threshold),
      actual: formatNumber(year.currentDaily),
      difference: formatNumber(
        year.currentDaily !== null && year.threshold !== null
          ? year.currentDaily - year.threshold
          : null,
      ),
      ratio: year.overLimit ? formatRatio(year.ratio) : null,
      reason: !year.applicable
        ? "A/B/C 缺失、参考月不合格或 B≤0，同比不适用"
        : `C=${formatNumber(year.referenceDaily)}，A=${formatNumber(year.a)}，B=${formatNumber(year.b)}，K=${formatNumber(year.k, 4)}，阈值=${formatNumber(year.threshold)}`,
      formula:
        "C=上年同月合格参考月日均；A=本月标杆总量÷本月自然日；B=参考月标杆总量÷参考月自然日；K=max(1,A/B)；阈值=C×K×1.20；当前日均>阈值则超标",
    },
    {
      key: "MONTH_ON_MONTH",
      label: "环比",
      status: !month.applicable
        ? "NOT_APPLICABLE"
        : month.overLimit
          ? "OVER_LIMIT"
          : "NORMAL",
      referencePeriod: "2026-05",
      baseline: formatNumber(month.referenceDaily),
      threshold: formatNumber(month.threshold),
      actual: formatNumber(month.currentDaily),
      difference: formatNumber(
        month.currentDaily !== null && month.threshold !== null
          ? month.currentDaily - month.threshold
          : null,
      ),
      ratio: month.overLimit ? formatRatio(month.ratio) : null,
      reason: !month.applicable
        ? "找不到最近合格参考月、A/B/C 缺失或 B≤0，环比不适用"
        : `最近合格参考月：C=${formatNumber(month.referenceDaily)}，A=${formatNumber(month.a)}，B=${formatNumber(month.b)}，K=${formatNumber(month.k, 4)}，阈值=${formatNumber(month.threshold)}`,
      formula:
        "C=当前月之前最近合格自然月日均；A=本月标杆月平均；B=参考月标杆月平均；K=max(1,A/B)；阈值=C×K×1.20；当前日均>阈值则超标",
    },
    {
      key: "RATED_BENCHMARK",
      label: "额定标杆",
      status: !ratedApplicable
        ? "NOT_APPLICABLE"
        : ratedOver
          ? "OVER_LIMIT"
          : "NORMAL",
      referencePeriod: "2026-06",
      baseline: formatNumber(benchmarkTotal),
      threshold: formatNumber(benchmarkTotal),
      actual: formatNumber(actualTotal),
      difference: formatNumber(
        actualTotal !== null && benchmarkTotal !== null ? actualTotal - benchmarkTotal : null,
      ),
      ratio: ratedOver ? formatRatio(ratedRatio) : null,
      reason: !ratedApplicable
        ? "当月日标杆不完整，额定标杆不适用"
        : benchmarkTotal === 0
          ? actualTotal === 0
            ? "阈值为 0 且实际为 0，结果正常"
            : "阈值为 0 且实际大于 0，结果超标；比例不显示无穷数"
          : ratedOver
            ? "实际总耗电量高于当月 1 日至月末日标杆值之和"
            : "实际总耗电量未超过当月日标杆值之和",
      formula:
        "额定标杆总量=当月1日至月末有效日值之和；实际总耗电量>总量则超标；总量=0时按实际是否为0判定且不伪造无穷比例",
    },
  ];
}

function makeBillingPoint(
  index: number,
  cityCode: string,
  status: AuditStatus,
  name: string,
  period = "2026-06",
  codeIndex = index,
): BillingPointDetail {
  const pointCity = city(cityCode);
  const id = `bp-${index}`;
  const code = `${cityCode}-BP-${String(codeIndex).padStart(4, "0")}`;
  const actual =
    status === "NOT_APPLICABLE" ? null : `${12850 + index * 137}.36`;
  const benchmark =
    status === "NOT_APPLICABLE" || status === "PENDING_REVIEW"
      ? null
      : status === "OVER_LIMIT"
        ? "11500.00"
        : `${Number(actual) + 1000}`;
  const deviation =
    status === "NOT_APPLICABLE"
      ? null
      : status === "OVER_LIMIT"
        ? `${11 + index}.74%`
        : "3.21%";
  const auditComparisons = comparisons(status, actual ?? "0", benchmark ?? "0");
  const eligible = status !== "PENDING_REVIEW";
  return {
    summary: {
      id,
      code,
      name,
      city: pointCity,
      period,
      address: `${pointCity.name}鼓楼区示范路 ${index} 号`,
      electricityCategory: index % 2 === 0 ? "商业用电" : "公共服务用电",
      paymentEligibility: eligible ? "ELIGIBLE" : "PENDING",
      actualEnergy: actual,
      benchmarkEnergy: benchmark,
      deviationRate: deviation,
      auditStatus: status,
      reportStatus: index === 1 ? "FINAL" : index === 2 ? "DRAFT" : "NONE",
    },
    overviewGroups: [
      {
        title: "身份与归属",
        fields: [
          { key: "pointType", label: "报账点类型", value: "铁塔电费报账点" },
          { key: "pointStatus", label: "报账点状态", value: "启用" },
          { key: "city", label: "所属地市", value: pointCity.name },
          { key: "district", label: "所属区县", value: "鼓楼区" },
          { key: "department", label: "所属部门", value: "网络部" },
          {
            key: "costCenter",
            label: "所属成本中心",
            value: `${pointCity.name}供电分公司`,
          },
          {
            key: "costCenterCode",
            label: "成本中心编码",
            value: `302${cityCode}${index}`,
          },
        ],
      },
      {
        title: "用电与计费",
        fields: [
          {
            key: "category",
            label: "用电类别",
            value: index % 2 === 0 ? "商业用电" : "公共服务用电",
          },
          { key: "voltage", label: "电压等级", value: "10kV" },
          { key: "billingMode", label: "计费方式", value: "非平峰谷" },
          { key: "supplyType", label: "供电类型", value: "直供电" },
          { key: "paymentCycle", label: "电费缴费周期", value: "月度" },
          { key: "lossMode", label: "电损计算方式", value: "按电量比例" },
        ],
      },
      {
        title: "合同与供应商",
        fields: [
          {
            key: "contractCode",
            label: "合同或固化编码",
            value: `ZDGH-JS-2018-${index}0295`,
          },
          { key: "contractName", label: "合同或固化名称", value: "站点供电合同" },
          { key: "contractStatus", label: "合同或固化状态", value: "生效" },
          { key: "contractEnd", label: "合同或固化期终", value: "2027-12-31" },
          {
            key: "supplier",
            label: "供应商名称",
            value: "国网江苏省电力有限公司",
          },
          {
            key: "supplierCode",
            label: "供应商编码",
            value: `JS-DL-${String(index).padStart(5, "0")}`,
          },
        ],
      },
      {
        title: "资源与电表",
        fields: [
          {
            key: "resourceCode",
            label: "关联资源编码",
            value: `ZY-JS-${cityCode}-${index}`,
          },
          { key: "resourceName", label: "关联资源名称", value: name },
          { key: "resourceType", label: "资源类型", value: "机房" },
          { key: "resourceStatus", label: "资源状态", value: "在网" },
          { key: "meterStatus", label: "电表状态", value: "在用" },
          {
            key: "meterCode",
            label: "关联电表编码",
            value: `1548${cityCode}${index}`,
          },
          { key: "meterAccount", label: "电表户号", value: `3205${cityCode}${index}` },
          { key: "meterMultiplier", label: "电表倍率", value: "1.0" },
        ],
      },
    ],
    payments: [
      {
        id: `${id}-payment-1`,
        billNumber: `PAY-202606-${index}01`,
        payee: "国网江苏省电力有限公司",
        electricityFee: "8564.28",
        billingEnergy: actual ?? "0",
        approvalStatus: eligible ? "APPROVED" : "PENDING",
        eligible,
        fieldGroups: [
          {
            title: "账单信息",
            fields: [
              {
                key: "billNo",
                label: "账单编号",
                value: `PAY-202606-${index}01`,
              },
              { key: "period", label: "账单期间", value: "2026-06" },
              {
                key: "payee",
                label: "收款单位",
                value: "国网江苏省电力有限公司",
              },
              {
                key: "amount",
                label: "电费金额",
                value: "8564.28",
                unit: "元",
              },
              {
                key: "energy",
                label: "账单电量",
                value: actual ?? "0",
                unit: "kWh",
              },
              {
                key: "approval",
                label: "审核状态",
                value: eligible ? "审核通过" : "待审核",
              },
            ],
          },
          {
            title: "结算信息",
            fields: [
              { key: "paymentDate", label: "缴费日期", value: "2026-07-08" },
              { key: "channel", label: "缴费渠道", value: "银行转账" },
              {
                key: "invoice",
                label: "发票号码",
                value: `INV202606${index}01`,
              },
              {
                key: "eligible",
                label: "参与审计",
                value: eligible ? "是" : "否",
              },
            ],
          },
        ],
      },
      {
        id: `${id}-payment-2`,
        billNumber: `PAY-202606-${index}02`,
        payee: "国网江苏省电力有限公司",
        electricityFee: "120.00",
        billingEnergy: "180.00",
        approvalStatus: "REJECTED",
        eligible: false,
        fieldGroups: [
          {
            title: "账单信息",
            fields: [
              {
                key: "billNo",
                label: "账单编号",
                value: `PAY-202606-${index}02`,
              },
              { key: "period", label: "账单期间", value: "2026-06" },
              { key: "amount", label: "电费金额", value: "120.00", unit: "元" },
              { key: "approval", label: "审核状态", value: "审核驳回" },
              { key: "eligible", label: "参与审计", value: "否" },
            ],
          },
        ],
      },
    ],
    meters: [
      {
        id: `${id}-meter-1`,
        meterNumber: `000${cityCode}${index}01`,
        previousReading: "10240.1200",
        currentReading: "16665.3000",
        multiplier: "2.0000",
        allocatedEnergy: actual ?? "0",
        valid: true,
        fieldGroups: [
          {
            title: "电表读数",
            fields: [
              {
                key: "meterNo",
                label: "电表编号",
                value: `000${cityCode}${index}01`,
              },
              { key: "readDate", label: "抄表日期", value: "2026-06-30" },
              { key: "previous", label: "上期读数", value: "10240.1200" },
              { key: "current", label: "本期读数", value: "16665.3000" },
              { key: "multiplier", label: "倍率", value: "2.0000" },
              {
                key: "allocated",
                label: "分摊后度数",
                value: actual ?? "0",
                unit: "kWh",
              },
              { key: "valid", label: "有效行", value: "是" },
            ],
          },
          {
            title: "采集信息",
            fields: [
              { key: "source", label: "采集来源", value: "月度抄表文件" },
              { key: "operator", label: "抄表人员", value: "系统导入" },
              { key: "remark", label: "备注", value: "读数连续" },
            ],
          },
        ],
      },
    ],
    benchmarks: [
      {
        id: `${id}-benchmark-1`,
        benchmarkType: "额定标杆电量",
        value: benchmark ?? "0",
        effectiveFrom: "2026-01",
        ruleVersion: "benchmark-v2026.1",
        fieldGroups: [
          {
            title: "标杆数据",
            fields: [
              { key: "type", label: "标杆类型", value: "额定标杆电量" },
              {
                key: "value",
                label: "标杆值",
                value: benchmark ?? "0",
                unit: "kWh",
              },
              { key: "effective", label: "生效期间", value: "2026-01" },
              { key: "rule", label: "规则版本", value: "benchmark-v2026.1" },
              {
                key: "boundary",
                label: "边界说明",
                value:
                  benchmark === null
                    ? "空值不参与额定标杆比较"
                    : "大于零时参与计算",
              },
            ],
          },
        ],
      },
    ],
    audit: {
      finalStatus: status,
      finalReason:
        status === "OVER_LIMIT"
          ? "同比与额定标杆均超出阈值，最终判定为额定标杆超标"
          : status === "PENDING_REVIEW"
            ? "存在待审核缴费明细，暂不形成最终结论"
            : status === "NOT_APPLICABLE"
              ? "关键比较基线缺失，本期不适用"
              : "三项比较均未超出阈值",
      ruleVersion: "audit-rule-v2026.1",
      calculatedAt: FIXED_TIME,
      eligibilityReason: eligible
        ? "至少一条审核通过的缴费明细，且有效电表行完整"
        : "缴费明细尚未审核通过",
      comparisons: auditComparisons,
    },
    draftId: index === 2 ? "draft-1" : null,
  };
}

function initialImports(): ImportBatch[] {
  return DATASET_TYPES.map((datasetType, index): ImportBatch => ({
    id: `batch-${index + 1}`,
    datasetType,
    period: "2026-06",
    fileName: `${datasetType.toLowerCase()}-2026-06.xlsx`,
    status: "ACTIVE",
    createdAt: `2026-07-0${index + 1}T09:00:00+08:00`,
    completedAt: `2026-07-0${index + 1}T09:03:00+08:00`,
    rowCount: ROW_COUNTS[datasetType],
    errorCount: 0,
    errors: [],
  })).concat({
    id: "batch-failed-1",
    datasetType: "PAYMENT",
    period: "2026-05",
    fileName: "payment-invalid.xlsx",
    status: "FAILED",
    createdAt: "2026-06-02T11:00:00+08:00",
    completedAt: "2026-06-02T11:01:00+08:00",
    rowCount: 0,
    errorCount: 2,
    errors: [
      { row: 5, column: "审核状态", message: "值不在允许范围内" },
      { row: 12, column: "报账点编码", message: "必填字段为空" },
    ],
  });
}

function initialUsers(): ManagedUser[] {
  const usernames = [
    "nanjing_user",
    "wuxi_user",
    "xuzhou_user",
    "changzhou_user",
    "suzhou_user",
    "nantong_user",
    "lianyungang_user",
    "huaian_user",
    "yancheng_user",
    "yangzhou_user",
    "zhenjiang_user",
    "taizhou_user",
    "suqian_user",
  ];
  return [
    {
      id: "user-1",
      username: "admin",
      displayName: "系统管理员",
      roles: ["SUPER_ADMIN"],
      city: null,
      enabled: true,
      mustChangePassword: false,
      updatedAt: FIXED_TIME,
      version: 0,
    },
    ...SCENARIO_CITIES.map((item, index): ManagedUser => ({
      id: `user-${index + 2}`,
      username: usernames[index] ?? `city_${item.code}`,
      displayName: `${item.name}用户`,
      roles: ["CITY_USER"],
      city: clone(item),
      enabled: index !== 12,
      mustChangePassword: true,
      updatedAt: FIXED_TIME,
      version: 0,
    })),
  ];
}

function initialDraft(): ReportDraft {
  const blocks: DraftBlock[] = [
    {
      id: "block-1",
      type: "HEADING",
      title: "报告标题",
      content: "南京科创园用电审计报告",
    },
    {
      id: "block-2",
      type: "SITUATION",
      title: "基本情况",
      content: "2026 年 6 月有效电表行分摊后度数合计 12987.36 kWh。",
    },
    {
      id: "block-3",
      type: "ANALYSIS",
      title: "审计分析",
      content: "同比与额定标杆比较均超过规则阈值，建议结合设备运行时长复核。",
    },
    {
      id: "block-4",
      type: "RECTIFICATION",
      title: "整改建议",
      content: "核查高耗能设备并建立月度跟踪台账，明确责任人与复核期限。",
    },
  ];
  return {
    id: "draft-1",
    billingPointId: "bp-2",
    billingPointName: "南京科创园",
    period: "2026-06",
    status: "EDITING",
    blocks,
    messages: [],
    versions: [
      {
        id: "draft-version-1",
        version: 1,
        reason: "INITIAL",
        summary: "系统基于审计结果生成初稿",
        createdAt: FIXED_TIME,
        blocks: clone(blocks),
      },
    ],
    updatedAt: FIXED_TIME,
    formalReportId: null,
    entityVersion: 1,
  };
}

function initialReports(): ReportSummary[] {
  const point = makeBillingPoint(1, "320100", "OVER_LIMIT", "南京中心广场");
  return [
    {
      id: "report-1",
      reportNumber: "BG-202606-000001",
      billingPointId: "bp-1",
      billingPointCode: point.summary.code,
      billingPointName: point.summary.name,
      city: point.summary.city,
      period: "2026-06",
      status: "FINAL",
      source: "SYSTEM",
      generatedAt: "2026-07-12T15:20:00+08:00",
      correctedAt: null,
      correctionCount: 0,
      wordFileName: "BG-202606-000001.docx",
      pdfFileName: "BG-202606-000001.pdf",
      summary: "该报账点额定标杆超标，已提出设备运行复核与月度跟踪建议。",
      archivedAudit: clone(point.audit.comparisons),
      latestAudit: point.audit.comparisons.map((item) =>
        item.key === "RATED_BENCHMARK"
          ? {
              ...item,
              actual: "13220.18",
              ratio: "14.96%",
              difference: "1720.18",
            }
          : item,
      ),
      corrections: [],
    },
    {
      id: "report-3",
      reportNumber: "BG-202605-000003",
      billingPointId: "bp-13",
      billingPointCode: point.summary.code,
      billingPointName: point.summary.name,
      city: point.summary.city,
      period: "2026-05",
      status: "FINAL",
      source: "SYSTEM",
      generatedAt: "2026-06-12T15:20:00+08:00",
      correctedAt: null,
      correctionCount: 0,
      wordFileName: "BG-202605-000003.docx",
      pdfFileName: "BG-202605-000003.pdf",
      summary: "该报账点 2026-05 已生成正式报告。",
      archivedAudit: clone(point.audit.comparisons),
      latestAudit: clone(point.audit.comparisons),
      corrections: [],
    },
    {
      id: "report-4",
      reportNumber: "BG-202603-000004",
      billingPointId: "bp-11",
      billingPointCode: point.summary.code,
      billingPointName: point.summary.name,
      city: point.summary.city,
      period: "2026-03",
      status: "FINAL",
      source: "SYSTEM",
      generatedAt: "2026-04-12T15:20:00+08:00",
      correctedAt: null,
      correctionCount: 0,
      wordFileName: "BG-202603-000004.docx",
      pdfFileName: "BG-202603-000004.pdf",
      summary: "该报账点 2026-03 已生成正式报告。",
      archivedAudit: clone(point.audit.comparisons),
      latestAudit: clone(point.audit.comparisons),
      corrections: [],
    },
    {
      id: "report-2",
      reportNumber: "BG-202505-000012",
      billingPointId: "bp-2",
      billingPointCode: "320100-BP-0002",
      billingPointName: "南京科创园",
      city: city("320100"),
      period: "2025-05",
      status: "HISTORICAL_IMPORTED",
      source: "HISTORICAL_IMPORT",
      generatedAt: "2026-07-01T10:00:00+08:00",
      correctedAt: null,
      correctionCount: 0,
      wordFileName: "南京科创园历史审计报告.docx",
      pdfFileName: "南京科创园历史审计报告.pdf",
      summary: "由历史 Word 文件导入并转换为 PDF 预览。",
      archivedAudit: [],
      latestAudit: [],
      corrections: [],
    },
  ];
}

function initialState(): ScenarioSnapshot {
  return {
    imports: initialImports(),
    billingPoints: [
      makeBillingPoint(11, "320100", "OVER_LIMIT", "南京中心广场", "2026-03", 1),
      makeBillingPoint(12, "320100", "OVER_LIMIT", "南京中心广场", "2026-04", 1),
      makeBillingPoint(13, "320100", "OVER_LIMIT", "南京中心广场", "2026-05", 1),
      makeBillingPoint(1, "320100", "OVER_LIMIT", "南京中心广场"),
      makeBillingPoint(2, "320100", "OVER_LIMIT", "南京科创园"),
      makeBillingPoint(3, "320100", "OVER_LIMIT", "南京滨江商务楼"),
      makeBillingPoint(4, "320100", "NORMAL", "南京公共服务中心"),
      makeBillingPoint(5, "320200", "PENDING_REVIEW", "无锡城市广场"),
      makeBillingPoint(6, "320500", "NOT_APPLICABLE", "苏州园区服务站"),
      makeBillingPoint(7, "320300", "NORMAL", "徐州综合服务楼"),
    ],
    drafts: [initialDraft()],
    reports: initialReports(),
    users: initialUsers(),
  };
}

export const BENCHMARK_RULES: readonly BenchmarkRule[] = [
  {
    key: "YEAR_ON_YEAR",
    name: "历史日均电量同比标杆",
    version: "audit-rule-v2026.1",
    description: "以上一年度同自然月的合格日均电量为 C，并按标杆变化系数上浮。",
    formula:
      "A=本月标杆总量÷本月自然日；B=上年同月标杆总量÷参考月自然日；K=max(1,A/B)；同比阈值=C×K×1.20",
    chain: [
      "筛选上年同月合格参考月 C",
      "计算 A/B 与 K=max(1,A/B)",
      "计算阈值 C×K×1.20",
      "当前日均大于阈值则超标",
    ],
    example: [
      { label: "参考月日均 C", value: "260.00 kWh/天" },
      { label: "调整系数 K", value: "1.1500" },
      { label: "阈值", value: "358.80 kWh/天（超标）" },
    ],
    boundaries: [
      "A/B/C 任一缺失或 B≤0 时不适用",
      "闰年按各自自然月天数计算",
      "参考月所有参与汇总缴费单必须审核通过",
    ],
    snapshotNote: "正式报告保存计算输入、输出与规则版本快照。",
  },
  {
    key: "MONTH_ON_MONTH",
    name: "历史日均电量环比标杆",
    version: "audit-rule-v2026.1",
    description: "向前寻找结束日最近的合格自然月，不强制固定上一月。",
    formula:
      "A=本月标杆月平均；B=参考月标杆月平均；K=max(1,A/B)；环比阈值=C×K×1.20",
    chain: [
      "向前查找最近合格自然月 C",
      "计算 A/B 与 K=max(1,A/B)",
      "计算阈值 C×K×1.20",
      "当前日均大于阈值则超标",
    ],
    example: [
      { label: "参考月日均 C", value: "354.84 kWh/天" },
      { label: "调整系数 K", value: "1.0000" },
      { label: "阈值", value: "425.81 kWh/天（正常）" },
    ],
    boundaries: [
      "A/B/C 任一缺失或 B≤0 时不适用",
      "跳过不完整或缴费单未全部审核通过的月份",
      "小数使用十进制定点精度",
    ],
    snapshotNote: "重导激活批次后重新查找上一有效月并生成新审计快照。",
  },
  {
    key: "RATED_BENCHMARK",
    name: "额定标杆（导入值）",
    version: "benchmark-v2026.1",
    description: "比较有效电表行分摊后度数合计与适用的额定标杆。",
    formula:
      "额定标杆总量=当月1日至月末有效日标杆值之和；实际总耗电量>额定标杆总量则超标",
    chain: [
      "汇总有效分摊后度数",
      "求和当月有效日标杆值",
      "比较实际总量与额定标杆总量",
      "形成最终类型",
    ],
    example: [
      { label: "实际总电量 A", value: "12,850.36 kWh" },
      { label: "额定标杆 D", value: "11,500.00 kWh" },
      { label: "偏差率", value: "11.74%（额定标杆超标）" },
    ],
    boundaries: [
      "日标杆不完整时不适用",
      "阈值为0且实际为0时正常",
      "阈值为0且实际>0时超标且比例不显示无穷数",
    ],
    snapshotNote: "报告始终展示生成时使用的规则版本，不随规则说明变化而回写。",
  },
];

export interface ScenarioStore {
  reset(): void;
  snapshot(): ScenarioSnapshot;
  listImports(): ImportBatch[];
  getImport(id: string): ImportBatch | undefined;
  createImport(input: CreateImportInput): ImportBatch;
  completeImport(id: string, outcome: "SUCCESS" | "FAILED"): ImportBatch;
  retryImport(id: string): ImportBatch;
  getDashboard(cityCode?: string): DashboardData;
  listBillingPoints(
    query: BillingPointQuery,
  ): PageResult<BillingPointDetail["summary"]>;
  getBillingPoint(id: string): BillingPointDetail | undefined;
  getDraft(id: string): ReportDraft | undefined;
  sendDraftMessage(id: string, input: SendDraftMessageInput): ReportDraft;
  restoreDraftVersion(id: string, versionId: string): ReportDraft;
  generateFormalReport(draftId: string): ReportSummary;
  listReports(query: ReportQuery): PageResult<ReportSummary>;
  getReport(id: string): ReportSummary | undefined;
  listHistoricalCandidates(query: {
    cityCode: string;
    keyword: string;
  }): HistoricalReportCandidate[];
  importHistoricalReport(input: {
    billingPointPeriodId: string;
    fileName: string;
  }): ReportSummary;
  correctReport(
    id: string,
    reason: string,
    correctedSummary?: string,
  ): ReportSummary;
  listUsers(page: number, size: number): PageResult<ManagedUser>;
  findUserByUsername(username: string): ManagedUser | undefined;
  createUser(input: {
    username: string;
    displayName: string;
    cityCode: string;
    enabled: boolean;
    initialPassword: string;
    confirmPassword: string;
  }): ManagedUser;
  updateUser(
    id: string,
    input: { displayName?: string; cityCode?: string; enabled?: boolean; version?: number },
  ): ManagedUser;
  resetUserPassword(id: string): ManagedUser;
  setUserEnabled(id: string, enabled: boolean): ManagedUser;
  changeOwnPassword(id: string): ManagedUser;
}

export function createScenarioStore(): ScenarioStore {
  let state = initialState();
  let sequence = 100;

  function now(): string {
    sequence += 1;
    const minute = String(sequence % 60).padStart(2, "0");
    return `2026-08-10T10:${minute}:00+08:00`;
  }

  function requiredImport(id: string): ImportBatch {
    const found = state.imports.find((item) => item.id === id);
    if (found === undefined) throw new Error("IMPORT_NOT_FOUND");
    return found;
  }

  function requiredDraft(id: string): ReportDraft {
    const found = state.drafts.find((item) => item.id === id);
    if (found === undefined) throw new Error("DRAFT_NOT_FOUND");
    return found;
  }

  function requiredReport(id: string): ReportSummary {
    const found = state.reports.find((item) => item.id === id);
    if (found === undefined) throw new Error("REPORT_NOT_FOUND");
    return found;
  }

  function requiredUser(id: string): ManagedUser {
    const found = state.users.find((item) => item.id === id);
    if (found === undefined) throw new Error("USER_NOT_FOUND");
    return found;
  }

  function currentPeriod(): string | null {
    const periods = [...new Set(state.imports.map((item) => item.period))]
      .filter((period) =>
        DATASET_TYPES.every((datasetType) =>
          state.imports.some(
            (item) =>
              item.period === period &&
              item.datasetType === datasetType &&
              item.status === "ACTIVE",
          ),
        ),
      )
      .sort()
      .reverse();
    return periods[0] ?? null;
  }

  function appendVersion(
    draft: ReportDraft,
    reason: "EDIT" | "IMAGE_ANALYSIS" | "RESTORE",
    summary: string,
  ): void {
    draft.versions.push({
      id: `draft-version-${++sequence}`,
      version: draft.versions.length + 1,
      reason,
      summary,
      createdAt: now(),
      blocks: clone(draft.blocks),
    });
    draft.updatedAt = now();
    draft.entityVersion += 1;
  }

  return {
    reset() {
      state = initialState();
      sequence = 100;
    },
    snapshot: () => clone(state),
    listImports: () => clone([...state.imports].reverse()),
    getImport: (id) => {
      const found = state.imports.find((item) => item.id === id);
      return found === undefined ? undefined : clone(found);
    },
    createImport(input) {
      const period = input.period ?? "2026-06";
      if (!/^\d{4}-(?:0[1-9]|1[0-2])$/.test(period)) {
        throw new Error("IMPORT_PERIOD_INVALID");
      }
      if (!/\.(?:xlsx|xls|csv)$/i.test(input.fileName)) {
        throw new Error("IMPORT_FILE_TYPE_INVALID");
      }
      const batch: ImportBatch = {
        id: `batch-${++sequence}`,
        ...input,
        period,
        status: "PROCESSING",
        createdAt: now(),
        completedAt: null,
        rowCount: 0,
        errorCount: 0,
        errors: [],
      };
      state.imports.push(batch);
      return clone(batch);
    },
    completeImport(id, outcome) {
      const batch = requiredImport(id);
      batch.completedAt = now();
      if (outcome === "FAILED") {
        batch.status = "FAILED";
        batch.errorCount = 2;
        batch.errors = [
          { row: 5, column: "报账点编码", message: "必填字段为空" },
          { row: 12, column: "期间", message: "值与所选数据期间不一致" },
        ];
        return clone(batch);
      }
      for (const existing of state.imports) {
        if (
          existing.id !== batch.id &&
          existing.datasetType === batch.datasetType &&
          existing.period === batch.period &&
          existing.status === "ACTIVE"
        ) {
          existing.status = "SUPERSEDED";
        }
      }
      batch.status = "ACTIVE";
      batch.rowCount = ROW_COUNTS[batch.datasetType];
      batch.errorCount = 0;
      batch.errors = [];
      return clone(batch);
    },
    retryImport(id) {
      const source = requiredImport(id);
      const batch = this.createImport({
        datasetType: source.datasetType,
        period: source.period,
        fileName: source.fileName.replace(/(\.[^.]+)$/, "-retry$1"),
      });
      return this.completeImport(batch.id, "SUCCESS");
    },
    getDashboard(cityCode) {
      const period = currentPeriod();
      const points = state.billingPoints
        .map((item) => item.summary)
        .filter(
          (item) =>
            (period === null || item.period === period) &&
            (cityCode === undefined || item.city.code === cityCode),
        );
      return {
        currentDataPeriod: period,
        availablePeriods: [
          ...new Set(
            state.billingPoints
              .map((item) => item.summary)
              .filter((item) => cityCode === undefined || item.city.code === cityCode)
              .map((item) => item.period),
          ),
        ].sort((left, right) => right.localeCompare(left)),
        imports: DATASET_TYPES.map((datasetType) => ({
          datasetType,
          activeBatch:
            clone(
              state.imports.find(
                (item) =>
                  item.datasetType === datasetType &&
                  item.period === period &&
                  item.status === "ACTIVE",
              ),
            ) ?? null,
        })),
        cityCount: cityCode === undefined ? SCENARIO_CITIES.length : 1,
        siteCount: new Set(points.map((item) => item.address)).size,
        lastUpdatedAt:
          period === null
            ? null
            : (state.imports
                .filter(
                  (item) => item.period === period && item.status === "ACTIVE",
                )
                .map((item) => item.completedAt)
                .filter((item): item is string => item !== null)
                .sort()
                .at(-1) ?? null),
        billingPointCount: points.length,
        normalBillingPointCount: points.filter(
          (item) => item.auditStatus === "NORMAL",
        ).length,
        overLimitBillingPointCount: points.filter(
          (item) => item.auditStatus === "OVER_LIMIT",
        ).length,
        pendingReviewCount: points.filter(
          (item) => item.auditStatus === "PENDING_REVIEW",
        ).length,
        draftReportCount: state.drafts.filter(
          (item) => item.status === "EDITING",
        ).length,
        pendingReportCount: state.drafts.filter(
          (item) => item.status === "EDITING",
        ).length,
        finalReportCount: state.reports.length,
        districtOverLimitCounts: [
          {
            name: "鼓楼区",
            count: points.filter((item) => item.auditStatus === "OVER_LIMIT")
              .length,
          },
          {
            name: "玄武区",
            count: points.filter(
              (item) => item.auditStatus === "PENDING_REVIEW",
            ).length,
          },
          {
            name: "建邺区",
            count: points.filter((item) => item.auditStatus === "NORMAL")
              .length,
          },
        ],
        overLimitTypeCounts: [
          {
            name: "同比",
            count: points.filter((item) => item.auditStatus === "OVER_LIMIT")
              .length,
          },
          {
            name: "环比",
            count: Math.max(
              0,
              points.filter((item) => item.auditStatus === "OVER_LIMIT")
                .length - 1,
            ),
          },
          {
            name: "额定标杆",
            count: points.filter((item) => item.auditStatus === "OVER_LIMIT")
              .length,
          },
          {
            name: "多项",
            count: points.filter((item) => item.auditStatus === "OVER_LIMIT")
              .length,
          },
        ],
        pendingTasks: [
          {
            id: "task-1",
            title: "复核超标报账点",
            description: `${points.filter((item) => item.auditStatus === "OVER_LIMIT").length} 个报账点待确认原因`,
            target: "/billing-points?status=OVER_LIMIT&page=1&size=20",
            severity: "DANGER",
            billingPointCode: "320100-BP-0002",
            billingPointName: "南京科创园",
            county: "鼓楼区",
            period: period ?? "—",
            actualAmount: "8564.28",
            overLimitType: "多项超标",
            maximumRatio: "23.32%",
          },
          {
            id: "task-2",
            title: "完善 AI 工作稿",
            description: "1 份结构化草稿等待确认",
            target: "/reports/drafts/draft-1",
            severity: "WARNING",
            billingPointCode: "320100-BP-0002",
            billingPointName: "南京科创园",
            county: "鼓楼区",
            period: period ?? "—",
            actualAmount: "8564.28",
            overLimitType: "多项超标",
            maximumRatio: "23.32%",
          },
        ],
      };
    },
    listBillingPoints(query) {
      const keyword = query.keyword.trim().toLowerCase();
      const filtered = state.billingPoints
        .map((item) => item.summary)
        .filter(
          (item) =>
            (query.cityCode.length === 0 ||
              item.city.code === query.cityCode) &&
            (query.period.length === 0 || item.period === query.period) &&
            (query.auditStatus.length === 0 ||
              item.auditStatus === query.auditStatus) &&
            (keyword.length === 0 ||
              [item.code, item.name, item.address].some((value) =>
                value.toLowerCase().includes(keyword),
              )),
        );
      const offset = (query.page - 1) * query.size;
      return {
        items: clone(filtered.slice(offset, offset + query.size)),
        page: query.page,
        size: query.size,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / query.size),
      };
    },
    getBillingPoint: (id) => {
      const found = state.billingPoints.find((item) => item.summary.id === id);
      return found === undefined ? undefined : clone(found);
    },
    getDraft: (id) => {
      const found = state.drafts.find((item) => item.id === id);
      return found === undefined ? undefined : clone(found);
    },
    sendDraftMessage(id, input) {
      const draft = requiredDraft(id);
      const resolvedIntent: Exclude<DraftIntent, "AUTO"> =
        input.intent === "AUTO"
          ? /修改|改为|补充|调整/.test(input.content)
            ? "EDIT"
            : "ASK"
          : input.intent;
      draft.messages.push({
        id: `message-${++sequence}`,
        role: "USER",
        intent: input.intent,
        content: input.content,
        imageNames: clone(input.imageNames),
        createdAt: now(),
      });
      if (resolvedIntent === "EDIT") {
        const block = draft.blocks.find(
          (item) => item.type === "RECTIFICATION",
        );
        if (block !== undefined) block.content = input.content;
        appendVersion(draft, "EDIT", "根据对话修改整改建议");
      } else if (resolvedIntent === "IMAGE_ANALYSIS") {
        if (input.imageNames.length === 0)
          throw new Error("DRAFT_IMAGE_REQUIRED");
        const imageName = input.imageNames[0] ?? "现场图片";
        draft.blocks.push({
          id: `block-${++sequence}`,
          type: "IMAGE",
          title: "现场图片分析",
          content: `${input.content}：已识别设备运行标识与现场环境，建议人工复核。`,
          imageName,
        });
        appendVersion(draft, "IMAGE_ANALYSIS", `分析图片 ${imageName}`);
      }
      const answer =
        resolvedIntent === "ASK"
          ? "根据归档审计输入，超标主要来自本期实际总电量高于同比与额定标杆基线；问答未修改正文。"
          : resolvedIntent === "EDIT"
            ? "已按要求更新整改建议并创建新版本。"
            : "已加入现场图片分析块并创建新版本。";
      draft.messages.push({
        id: `message-${++sequence}`,
        role: "ASSISTANT",
        intent: resolvedIntent,
        content: answer,
        imageNames: [],
        createdAt: now(),
      });
      return clone(draft);
    },
    restoreDraftVersion(id, versionId) {
      const draft = requiredDraft(id);
      const version = draft.versions.find((item) => item.id === versionId);
      if (version === undefined) throw new Error("DRAFT_VERSION_NOT_FOUND");
      draft.blocks = clone(version.blocks);
      appendVersion(draft, "RESTORE", `恢复自版本 V${version.version}`);
      return clone(draft);
    },
    generateFormalReport(draftId) {
      const draft = requiredDraft(draftId);
      if (draft.formalReportId !== null)
        return clone(requiredReport(draft.formalReportId));
      const point = state.billingPoints.find(
        (item) => item.summary.id === draft.billingPointId,
      );
      if (point === undefined) throw new Error("BILLING_POINT_NOT_FOUND");
      const reportNumber = `BG-${draft.period.replace("-", "")}-${String(
        state.reports.length + 1,
      ).padStart(6, "0")}`;
      const report: ReportSummary = {
        id: `report-${++sequence}`,
        reportNumber,
        billingPointId: draft.billingPointId,
        billingPointCode: point.summary.code,
        billingPointName: point.summary.name,
        city: clone(point.summary.city),
        period: draft.period,
        status: "FINAL",
        source: "SYSTEM",
        generatedAt: now(),
        correctedAt: null,
        correctionCount: 0,
        wordFileName: `${reportNumber}.docx`,
        pdfFileName: `${reportNumber}.pdf`,
        summary: draft.blocks.map((block) => block.content).join("\n"),
        archivedAudit: clone(point.audit.comparisons),
        latestAudit: clone(point.audit.comparisons),
        corrections: [],
      };
      state.reports.push(report);
      draft.formalReportId = report.id;
      draft.status = "FINALIZED";
      draft.entityVersion += 1;
      point.summary.reportStatus = "FINAL";
      return clone(report);
    },
    listReports(query) {
      const keyword = query.keyword.trim().toLowerCase();
      const filtered = state.reports.filter(
        (item) =>
          (query.cityCode.length === 0 || item.city.code === query.cityCode) &&
          (query.district.length === 0 || item.district === query.district) &&
          (query.period.length === 0 || item.period === query.period) &&
          (query.source.length === 0 || item.source === query.source) &&
          (keyword.length === 0 ||
            [
              item.reportNumber,
              item.billingPointCode,
              item.billingPointName,
            ].some((value) => value.toLowerCase().includes(keyword))),
      );
      const offset = (query.page - 1) * query.size;
      return {
        items: clone(filtered.slice(offset, offset + query.size)),
        page: query.page,
        size: query.size,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / query.size),
      };
    },
    getReport: (id) => {
      const found = state.reports.find((item) => item.id === id);
      return found === undefined ? undefined : clone(found);
    },
    listHistoricalCandidates(query) {
      const keyword = query.keyword.trim().toLowerCase();
      const filtered = state.billingPoints
        .map((item) => item.summary)
        .filter(
          (item) =>
            (query.cityCode.length === 0 ||
              item.city.code === query.cityCode) &&
            !state.reports.some(
              (report) =>
                report.billingPointCode === item.code &&
                report.period === item.period,
            ) &&
            (keyword.length === 0 ||
              [item.code, item.name].some((value) =>
                value.toLowerCase().includes(keyword),
              )),
        )
        .map(
          (item): HistoricalReportCandidate => ({
            billingPointPeriodId: item.id,
            billingPointCode: item.code,
            billingPointName: item.name,
            cityCode: item.city.code,
            cityName: item.city.name,
            period: item.period,
            overLimitType: item.overLimitType,
            maxRatio: item.deviationRate,
          }),
        )
        .sort(
          (left, right) =>
            left.billingPointCode.localeCompare(right.billingPointCode) ||
            right.period.localeCompare(left.period),
        );
      return clone(filtered);
    },
    importHistoricalReport(input) {
      if (!/\.docx?$/i.test(input.fileName))
        throw new Error("HISTORICAL_FILE_TYPE_INVALID");
      const point = state.billingPoints.find(
        (item) => item.summary.id === input.billingPointPeriodId,
      );
      if (point === undefined) throw new Error("BILLING_POINT_NOT_FOUND");
      if (
        state.reports.some(
          (report) =>
            report.billingPointCode === point.summary.code &&
            report.period === point.summary.period,
        )
      ) {
        throw new Error("HISTORICAL_REPORT_ALREADY_EXISTS");
      }
      const reportNumber = `BG-${point.summary.period.replace("-", "")}-${String(
        state.reports.length + 1,
      ).padStart(6, "0")}`;
      const report: ReportSummary = {
        id: `report-${++sequence}`,
        reportNumber,
        billingPointId: input.billingPointPeriodId,
        billingPointCode: point.summary.code,
        billingPointName: point.summary.name,
        city: clone(point.summary.city),
        period: point.summary.period,
        status: "HISTORICAL_IMPORTED",
        source: "HISTORICAL_IMPORT",
        generatedAt: now(),
        correctedAt: null,
        correctionCount: 0,
        wordFileName: input.fileName,
        pdfFileName: input.fileName.replace(/\.docx?$/i, ".pdf"),
        summary: "历史 Word 文件已保留，并生成 PDF 预览。",
        archivedAudit: [],
        latestAudit: [],
        corrections: [],
      };
      state.reports.push(report);
      return clone(report);
    },
    correctReport(id, reason, correctedSummary) {
      const report = requiredReport(id);
      if (reason.trim().length === 0)
        throw new Error("CORRECTION_REASON_REQUIRED");
      const occurredAt = now();
      report.archivedAudit = clone(report.latestAudit);
      report.status = "CORRECTED";
      report.correctedAt = occurredAt;
      report.correctionCount += 1;
      if (
        correctedSummary !== undefined &&
        correctedSummary.trim().length > 0
      ) {
        report.summary = correctedSummary.trim();
      }
      report.corrections.push({
        reason: reason.trim(),
        operator: "系统管理员",
        occurredAt,
        summary: "已用最新重算快照覆盖当前正式内容与文件，报告编号保持不变。",
      });
      return clone(report);
    },
    listUsers(page, size) {
      const offset = (page - 1) * size;
      return {
        items: clone(state.users.slice(offset, offset + size)),
        page,
        size,
        totalElements: state.users.length,
        totalPages: Math.ceil(state.users.length / size),
      };
    },
    findUserByUsername(username) {
      const found = state.users.find((item) => item.username === username);
      return found === undefined ? undefined : clone(found);
    },
    createUser(input) {
      if (state.users.some((item) => item.username === input.username)) {
        throw new Error("USERNAME_ALREADY_EXISTS");
      }
      const created: ManagedUser = {
        id: `user-${++sequence}`,
        username: input.username,
        displayName: input.displayName,
        roles: ["CITY_USER"],
        city: city(input.cityCode),
        enabled: input.enabled,
        mustChangePassword: false,
        updatedAt: now(),
        version: 0,
      };
      state.users.push(created);
      return clone(created);
    },
    updateUser(id, input) {
      const user = requiredUser(id);
      if (input.displayName !== undefined) user.displayName = input.displayName.trim();
      if (input.cityCode !== undefined) user.city = city(input.cityCode);
      if (input.enabled !== undefined) user.enabled = input.enabled;
      user.version += 1;
      user.updatedAt = now();
      return clone(user);
    },
    resetUserPassword(id) {
      const user = requiredUser(id);
      user.mustChangePassword = false;
      user.version += 1;
      user.updatedAt = now();
      return clone(user);
    },
    setUserEnabled(id, enabled) {
      const user = requiredUser(id);
      if (user.roles.includes("SUPER_ADMIN") && !enabled) {
        throw new Error("SUPER_ADMIN_CANNOT_BE_DISABLED");
      }
      user.enabled = enabled;
      user.updatedAt = now();
      return clone(user);
    },
    changeOwnPassword(id) {
      const user = requiredUser(id);
      user.mustChangePassword = false;
      user.version += 1;
      user.updatedAt = now();
      return clone(user);
    },
  };
}

export const mockScenario = createScenarioStore();
