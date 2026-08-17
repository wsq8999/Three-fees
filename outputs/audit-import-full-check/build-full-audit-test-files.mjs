import fs from "node:fs/promises";
import path from "node:path";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";

const root = "D:/Three-fees";
const outputDir = path.join(root, "outputs/audit-import-full-check");

const catalogFiles = {
  billingPoint: path.join(root, "backend/src/main/resources/catalog-billing-point.tsv"),
  payment: path.join(root, "backend/src/main/resources/catalog-payment.tsv"),
  meter: path.join(root, "backend/src/main/resources/catalog-meter-reading.tsv"),
  benchmark: path.join(root, "backend/src/main/resources/catalog-benchmark.tsv"),
};

const periods = ["2025-06", "2026-05", "2026-06"];
const currentPeriod = "2026-06";
const city = "南京市";
const district = "测试区";
const uplift = 1.2;
const unitPrice = 0.8;

const points = [
  {
    code: "TEST-AUDIT-FULL-001",
    name: "完整验证001-同比环比额定全超",
    energies: { "2025-06": 3000, "2026-05": 3000, "2026-06": 5000 },
    benchmarks: { "2025-06": 3000, "2026-05": 3000, "2026-06": 3000 },
    expected: { yoy: "OVER_LIMIT", mom: "OVER_LIMIT", rated: "OVER_LIMIT", overall: "MULTIPLE" },
  },
  {
    code: "TEST-AUDIT-FULL-002",
    name: "完整验证002-全部正常",
    energies: { "2025-06": 3000, "2026-05": 3000, "2026-06": 2800 },
    benchmarks: { "2025-06": 3000, "2026-05": 3000, "2026-06": 3000 },
    expected: { yoy: "NORMAL", mom: "NORMAL", rated: "NORMAL", overall: "NONE" },
  },
  {
    code: "TEST-AUDIT-FULL-003",
    name: "完整验证003-仅同比超标",
    energies: { "2025-06": 2400, "2026-05": 3000, "2026-06": 3300 },
    benchmarks: { "2025-06": 3600, "2026-05": 3600, "2026-06": 3600 },
    expected: { yoy: "OVER_LIMIT", mom: "NORMAL", rated: "NORMAL", overall: "YOY" },
  },
  {
    code: "TEST-AUDIT-FULL-004",
    name: "完整验证004-仅环比超标",
    energies: { "2025-06": 3000, "2026-05": 2400, "2026-06": 3300 },
    benchmarks: { "2025-06": 3600, "2026-05": 3600, "2026-06": 3600 },
    expected: { yoy: "NORMAL", mom: "OVER_LIMIT", rated: "NORMAL", overall: "MOM" },
  },
  {
    code: "TEST-AUDIT-FULL-005",
    name: "完整验证005-仅额定超标",
    energies: { "2025-06": 3000, "2026-05": 3000, "2026-06": 3300 },
    benchmarks: { "2025-06": 3000, "2026-05": 3000, "2026-06": 3000 },
    expected: { yoy: "NORMAL", mom: "NORMAL", rated: "OVER_LIMIT", overall: "RATED" },
  },
];

async function headers(file) {
  const content = await fs.readFile(file, "utf8");
  return content
    .trim()
    .split(/\r?\n/)
    .map((line) => line.split("\t")[2]);
}

function row(headers, values) {
  return headers.map((header) => values[header] ?? "");
}

function ymStart(period) {
  return `${period}-01`;
}

function ymEnd(period) {
  const [year, month] = period.split("-").map(Number);
  return new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10);
}

function days(period) {
  const [year, month] = period.split("-").map(Number);
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

function daily(total, period) {
  return total / days(period);
}

function historicalThreshold(point, referencePeriod) {
  const currentDailyBenchmark = daily(point.benchmarks[currentPeriod], currentPeriod);
  const referenceDailyBenchmark = daily(point.benchmarks[referencePeriod], referencePeriod);
  const coefficient =
    currentDailyBenchmark / referenceDailyBenchmark > 1
      ? currentDailyBenchmark / referenceDailyBenchmark
      : 1;
  return daily(point.energies[referencePeriod], referencePeriod) * coefficient * uplift;
}

function exceedRatio(currentDaily, thresholdDaily) {
  return currentDaily > thresholdDaily ? (currentDaily / thresholdDaily - 1) * 100 : 0;
}

function sheetTitleFormat(range) {
  range.format = {
    fill: "#1F4E79",
    font: { bold: true, color: "#FFFFFF" },
    wrapText: true,
  };
}

async function writeWorkbook(fileName, sheetName, headers, rows) {
  const workbook = Workbook.create();
  const sheet = workbook.worksheets.add(sheetName);
  sheet.showGridLines = false;
  sheet.getRangeByIndexes(0, 0, rows.length + 1, headers.length).values = [headers, ...rows];
  const used = sheet.getRangeByIndexes(0, 0, rows.length + 1, headers.length);
  sheetTitleFormat(sheet.getRangeByIndexes(0, 0, 1, headers.length));
  used.format.borders = { preset: "all", style: "thin", color: "#D9E2EC" };
  used.format.autofitColumns();
  sheet.getRangeByIndexes(0, 0, rows.length + 1, headers.length).format.autofitRows();
  sheet.freezePanes.freezeRows(1);
  const output = await SpreadsheetFile.exportXlsx(workbook);
  const fullPath = path.join(outputDir, fileName);
  await output.save(fullPath);
  return fullPath;
}

function billingPointRow(point, period) {
  return {
    审核状态: "审核通过",
    报账点编码: point.code,
    报账点名称: point.name,
    报账点类型: "铁塔电费报账点",
    所属成本中心: "测试成本中心",
    成本中心编码: "CC-FULL-TEST",
    所属地市: city,
    所属区县: district,
    所属部门: "测试部门",
    报账点状态: "启用",
    报账点计量倍数: "1",
    计划缴费日期: ymEnd(period),
    最后报账期始: ymStart(period),
    最后报账期终: ymEnd(period),
    用电类别: "基站用电",
    电压等级: "低压",
    计费方式: "直供",
    建站用电期始: "2024-01-01",
    是否转改直站点: "否",
    摘要: "同比环比额定完整验证数据",
    关键字: "audit-full-test",
    供电类型: "市电",
    电费缴费周期: "月",
    电损计算方式: "无",
    供应商名称: "测试供电公司",
    供应商编码: "SUP-FULL-TEST",
    关联资源编码: `${point.code}-SITE`,
    关联资源名称: `${point.name}站址`,
    资源类型: "铁塔站址",
    业务类型: "基站",
    主设备功率: "10",
    空调总功率: "5",
    铁塔空调总额定功率: "15",
    资源状态: "在用",
    铁塔站址编码: `${point.code}-SITE`,
    产权性质: "自有",
    产权单位: "测试单位",
    入网时间: "2024-01-01",
    关联电表编码: `${point.code}-METER`,
    电表户号: `${point.code}-ACCOUNT`,
    电表状态: "启用",
    是否多机房共用: "否",
    电表倍率: "1",
  };
}

function paymentRow(point, period) {
  const energy = point.energies[period];
  const periodDays = days(period);
  const paymentCode = `${point.code}-${period}`;
  return {
    审核状态: "审核通过",
    上次审核时间: ymEnd(period),
    上次审核人: "测试审核人",
    审核结果: "通过",
    缴费单编码: paymentCode,
    所属地市: city,
    所属区县: district,
    报账点编码: point.code,
    报账点名称: point.name,
    报账点类型: "铁塔电费报账点",
    报账点计量倍数: "1",
    合同编码: `${point.code}-CONTRACT`,
    合同名称: `${point.name}合同`,
    购电方式: "直供",
    供电类型: "市电",
    电损计算方式: "无",
    缴费申请日期: ymEnd(period),
    缴费期始: ymStart(period),
    缴费期终: ymEnd(period),
    缴费天数: String(periodDays),
    日均耗电量: daily(energy, period).toFixed(6),
    实际报账金额: (energy * unitPrice).toFixed(2),
    系统计算金额: (energy * unitPrice).toFixed(2),
    实际总耗电量: String(energy),
    数据来源: "测试导入",
    录入人: "测试人员",
    所属部门: "测试部门",
    所属成本中心: "CC-FULL-TEST",
    所属成本中心名称: "测试成本中心",
    业务大类: "电费",
    业务小类: "基站电费",
    业务活动: "测试",
    "历史日均电量标杆-同比": "",
    "历史日均电量标杆-环比": "",
    额定功率标杆: String(point.benchmarks[period]),
    关键字: "audit-full-test",
    是否为首单: period === "2025-06" ? "是" : "否",
  };
}

function meterRow(point, period) {
  const energy = point.energies[period];
  return {
    报账点名称: point.name,
    报账点编码: point.code,
    缴费单编码: `${point.code}-${period}`,
    缴费期始: ymStart(period),
    缴费期终: ymEnd(period),
    电表编码: `${point.code}-METER`,
    电表户号: `${point.code}-ACCOUNT`,
    电表倍率: "1",
    实际分摊比例: "1",
    上次分摊比例: "1",
    电表上期读数: "0",
    本期读数: String(energy),
    电表耗电量: String(energy),
    分摊后度数: String(energy),
    单价1: String(unitPrice),
    电量1: String(energy),
    电费不含税金额: (energy * unitPrice).toFixed(2),
    电费税金: "0",
    电损不含税金额: "0",
    电损税金: "0",
  };
}

function benchmarkRow(point, period) {
  const periodDays = days(period);
  const total = point.benchmarks[period];
  const values = {
    报账点编码: point.code,
    报账点名称: point.name,
    报账点状态: "启用",
    地市: city,
    区县: district,
    年份: period.slice(0, 4),
    月份: String(Number(period.slice(5, 7))),
    月总标杆: String(total),
  };
  for (let day = 1; day <= 31; day += 1) {
    values[String(day)] = day <= periodDays ? (total / periodDays).toFixed(6) : "";
  }
  return values;
}

function expectedRows() {
  const rows = [
    [
      "报账点编码",
      "报账点名称",
      "账期",
      "本期电量",
      "去年同期电量",
      "上一期电量",
      "本期额定标杆",
      "本期日均",
      "同比阈值日均",
      "同比结果",
      "环比阈值日均",
      "环比结果",
      "额定结果",
      "预期总状态",
      "说明",
    ],
  ];
  for (const point of points) {
    const currentDaily = daily(point.energies[currentPeriod], currentPeriod);
    const yoyThreshold = historicalThreshold(point, "2025-06");
    const momThreshold = historicalThreshold(point, "2026-05");
    const ratedTotal = point.benchmarks[currentPeriod];
    rows.push([
      point.code,
      point.name,
      currentPeriod,
      point.energies[currentPeriod],
      point.energies["2025-06"],
      point.energies["2026-05"],
      ratedTotal,
      Number(currentDaily.toFixed(6)),
      Number(yoyThreshold.toFixed(6)),
      point.expected.yoy,
      Number(momThreshold.toFixed(6)),
      point.expected.mom,
      point.expected.rated,
      point.expected.overall,
      `同比超标率约 ${exceedRatio(currentDaily, yoyThreshold).toFixed(2)}%；环比超标率约 ${exceedRatio(currentDaily, momThreshold).toFixed(2)}%；额定超标率约 ${
        point.energies[currentPeriod] > ratedTotal
          ? ((point.energies[currentPeriod] / ratedTotal - 1) * 100).toFixed(2)
          : "0.00"
      }%。`,
    ]);
  }
  return rows;
}

async function writeExpectedWorkbook() {
  const workbook = Workbook.create();
  const sheet = workbook.worksheets.add("预期结果");
  sheet.showGridLines = false;
  const rows = expectedRows();
  sheet.getRangeByIndexes(0, 0, rows.length, rows[0].length).values = rows;
  sheetTitleFormat(sheet.getRangeByIndexes(0, 0, 1, rows[0].length));
  const used = sheet.getRangeByIndexes(0, 0, rows.length, rows[0].length);
  used.format.borders = { preset: "all", style: "thin", color: "#D9E2EC" };
  used.format.autofitColumns();
  sheet.getRange("D2:H6").format.numberFormat = "#,##0.00";
  sheet.getRange("I2:I6").format.numberFormat = "#,##0.00";
  sheet.getRange("K2:K6").format.numberFormat = "#,##0.00";
  sheet.freezePanes.freezeRows(1);
  const preview = await workbook.render({ sheetName: "预期结果", autoCrop: "all", scale: 1, format: "png" });
  await fs.writeFile(
    path.join(outputDir, "预期结果_同比环比额定完整验证.xlsx.preview.png"),
    new Uint8Array(await preview.arrayBuffer()),
  );
  const output = await SpreadsheetFile.exportXlsx(workbook);
  const fullPath = path.join(outputDir, "预期结果_同比环比额定完整验证.xlsx");
  await output.save(fullPath);
  const inspect = await workbook.inspect({
    kind: "table",
    range: "预期结果!A1:O6",
    include: "values",
    tableMaxRows: 6,
    tableMaxCols: 15,
    maxChars: 5000,
  });
  console.log(inspect.ndjson);
  return fullPath;
}

await fs.mkdir(outputDir, { recursive: true });

const nodeModules = path.join(outputDir, "node_modules");
try {
  await fs.symlink(
    "C:/Users/asus/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules",
    nodeModules,
    "junction",
  );
} catch (error) {
  if (error.code !== "EEXIST") {
    throw error;
  }
}

const billingHeaders = await headers(catalogFiles.billingPoint);
const paymentHeaders = await headers(catalogFiles.payment);
const meterHeaders = await headers(catalogFiles.meter);
const benchmarkHeaders = await headers(catalogFiles.benchmark);

const billingRows = [];
const paymentRows = [];
const meterRows = [];
const benchmarkRows = [];

for (const period of periods) {
  for (const point of points) {
    billingRows.push(row(billingHeaders, billingPointRow(point, period)));
    paymentRows.push(row(paymentHeaders, paymentRow(point, period)));
    meterRows.push(row(meterHeaders, meterRow(point, period)));
    benchmarkRows.push(row(benchmarkHeaders, benchmarkRow(point, period)));
  }
}

const outputs = [];
outputs.push(
  await writeWorkbook("01_报账点清单_同比环比额定完整验证.xlsx", "报账点清单", billingHeaders, billingRows),
);
outputs.push(
  await writeWorkbook("02_缴费明细_同比环比额定完整验证.xlsx", "缴费明细", paymentHeaders, paymentRows),
);
outputs.push(
  await writeWorkbook("03_电表读数_同比环比额定完整验证.xlsx", "电表读数", meterHeaders, meterRows),
);
outputs.push(
  await writeWorkbook("04_标杆值_同比环比额定完整验证.xlsx", "标杆值", benchmarkHeaders, benchmarkRows),
);
outputs.push(await writeExpectedWorkbook());

console.log(JSON.stringify({ outputDir, files: outputs }, null, 2));
