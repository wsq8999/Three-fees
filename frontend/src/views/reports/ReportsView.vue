<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Download, Refresh, Search, Upload } from "@element-plus/icons-vue";
import type { TableInstance, UploadFile } from "element-plus";
import { ElMessage } from "element-plus";

import { businessApi, formatPercent, saveBlob, triggerBrowserDownload } from "@/api/business-api";
import PageState from "@/components/PageState.vue";
import { useSessionStore } from "@/stores/session";
import type {
  BusinessCity,
  HistoricalReportBillingPoint,
  HistoricalReportPeriod,
  PageResult,
  ReportSummary,
} from "@/types/business";


const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const loading = ref(false);
const importing = ref(false);
const downloadingId = ref("");
const errorMessage = ref("");
const pageData = ref<PageResult<ReportSummary> | null>(null);
const optionReports = ref<ReportSummary[]>([]);
const historicalPoints = ref<HistoricalReportBillingPoint[]>([]);
const historicalPeriods = ref<HistoricalReportPeriod[]>([]);
const importOptionsLoading = ref(false);
const periodLoadFinished = ref(false);
const importVisible = ref(false);
const importSuccessVisible = ref(false);
const importError = ref("");
const selectedRows = ref<ReportSummary[]>([]);
const tableRef = ref<TableInstance>();
const focusedReport = ref<ReportSummary | null>(null);

const importForm = reactive({
  pointKey: "",
  billingPointCode: "",
  cityCode: "",
  period: "",
  file: null as File | null,
});

const filters = reactive({
  keyword: String(route.query.keyword ?? ""),
  period: String(route.query.period ?? ""),
  cityCode: session.currentUser?.city?.code ?? String(route.query.city ?? ""),
  district: String(route.query.district ?? ""),
  source: "" as ReportSummary["source"] | "",
  page: Number(route.query.page ?? 1),
  size: Number(route.query.size ?? 10),
});

const cityLocked = computed(() => session.currentUser?.city != null);
const canImport = computed(
  () => session.hasRole("SUPER_ADMIN") || session.hasRole("CITY_USER"),
);

const periodOptions = computed(() =>
  uniqueOptions(optionReports.value.map((item) => item.period)).sort().reverse(),
);
const cityOptions = computed<BusinessCity[]>(() => {
  const map = new Map<string, BusinessCity>();
  for (const report of optionReports.value) map.set(report.city.code, report.city);
  return Array.from(map.values());
});
const districtOptions = computed(() =>
  uniqueOptions(
    optionReports.value
      .filter(
        (item) =>
          filters.cityCode.length === 0 || item.city.code === filters.cityCode,
      )
      .map((item) => item.district ?? ""),
  ),
);
const range = computed(() => {
  const total = pageData.value?.totalElements ?? 0;
  if (total === 0) return "已显示 0-0 条，共 0 条";
  const start = (filters.page - 1) * filters.size + 1;
  const end = Math.min(filters.page * filters.size, total);
  return `已显示 ${start}-${end} 条，共 ${total} 条`;
});

function uniqueOptions(values: string[]): string[] {
  return Array.from(new Set(values.filter((value) => value.length > 0)));
}

function hasText(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}


function reportQuery(page = filters.page, size = filters.size) {
  return {
    cityCode: filters.cityCode,
    district: filters.district,
    period: filters.period,
    keyword: filters.keyword,
    source: filters.source,
    page,
    size,
  };
}

async function loadOptions(): Promise<void> {
  const result = await businessApi.reports.list({
    cityCode: session.currentUser?.city?.code ?? "",
    district: "",
    period: "",
    keyword: "",
    source: "",
    page: 1,
    size: 100,
  });
  optionReports.value = result.items;
}

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    const result = await businessApi.reports.list(reportQuery());
    if (focusedReport.value !== null) {
      result.items = [
        focusedReport.value,
        ...result.items.filter((item) => item.id !== focusedReport.value?.id),
      ];
    }
    pageData.value = result;
    selectedRows.value = [];
    await nextTick();
    restoreHorizontalScroll();
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "历史报告加载失败";
  } finally {
    loading.value = false;
  }
}

async function syncRouteAndLoad(resetPage = false): Promise<void> {
  if (resetPage) filters.page = 1;
  await router.replace({
    path: "/reports/history",
    query: {
      ...(filters.keyword ? { keyword: filters.keyword } : {}),
      ...(filters.period ? { period: filters.period } : {}),
      ...(filters.cityCode ? { city: filters.cityCode } : {}),
      ...(filters.district ? { district: filters.district } : {}),
      page: String(filters.page),
      size: String(filters.size),
    },
  });
  await load();
}

function reset(): void {
  Object.assign(filters, {
    keyword: "",
    period: "",
    cityCode: session.currentUser?.city?.code ?? "",
    district: "",
    source: "",
    page: 1,
  });
  void syncRouteAndLoad();
}

async function openImport(): Promise<void> {
  if (!canImport.value) return;
  importError.value = "";
  Object.assign(importForm, {
    pointKey: "",
    billingPointCode: "",
    cityCode: "",
    period: "",
    file: null,
  });
  historicalPoints.value = [];
  historicalPeriods.value = [];
  periodLoadFinished.value = false;
  importVisible.value = true;
  importOptionsLoading.value = true;
  try {
    historicalPoints.value = await businessApi.reports.listHistoricalBillingPoints({
      cityCode: session.currentUser?.city?.code ?? "",
      keyword: "",
    });
  } catch (error) {
    importError.value = error instanceof Error ? error.message : "报账点加载失败";
  } finally {
    importOptionsLoading.value = false;
  }
}

async function importPointChanged(value?: string): Promise<void> {
  const selectedKey = value ?? importForm.pointKey;
  importForm.pointKey = selectedKey;
  const parts = selectedKey.split("::");
  if (parts.length === 2) {
    importForm.cityCode = parts[0] ?? "";
    importForm.billingPointCode = parts[1] ?? "";
  }
  if (!hasText(importForm.billingPointCode)) {
    const selectedByLabel = historicalPoints.value.find(
      (item) => pointOptionLabel(item) === selectedKey || item.billingPointCode === selectedKey,
    );
    if (selectedByLabel) {
      importForm.pointKey = pointKey(selectedByLabel);
      importForm.cityCode = selectedByLabel.cityCode;
      importForm.billingPointCode = selectedByLabel.billingPointCode;
    }
  }
  importForm.period = "";
  historicalPeriods.value = [];
  periodLoadFinished.value = false;
  importError.value = "";
  if (!hasText(importForm.billingPointCode)) return;
  const selected = historicalPoints.value.find(
    (item) =>
      item.billingPointCode === importForm.billingPointCode &&
      (!importForm.cityCode || item.cityCode === importForm.cityCode),
  );
  importForm.cityCode = selected?.cityCode ?? importForm.cityCode;
  importOptionsLoading.value = true;
  try {
    const periods = await businessApi.reports.listHistoricalPeriods({
      billingPointCode: importForm.billingPointCode,
      cityCode: importForm.cityCode || session.currentUser?.city?.code || "",
    });
    historicalPeriods.value = periods;
    if (periods.length > 0) {
      importForm.period = periods[0]?.period ?? "";
    }
    periodLoadFinished.value = true;
  } catch (error) {
    importError.value = error instanceof Error ? error.message : "账期加载失败";
    periodLoadFinished.value = true;
  } finally {
    importOptionsLoading.value = false;
  }
}

function importPeriodChanged(): void {
  importError.value = "";
}

async function searchImportPoints(keyword: string): Promise<void> {
  importOptionsLoading.value = true;
  try {
    historicalPoints.value = await businessApi.reports.listHistoricalBillingPoints({
      cityCode: session.currentUser?.city?.code ?? "",
      keyword,
    });
  } catch (error) {
    importError.value = error instanceof Error ? error.message : "报账点加载失败";
  } finally {
    importOptionsLoading.value = false;
  }
}

function pointOptionLabel(point: HistoricalReportBillingPoint): string {
  return `${point.billingPointCode} ｜ ${point.billingPointName} ｜ ${point.cityName}`;
}

function periodOptionLabel(candidate: HistoricalReportPeriod): string {
  return candidate.period;
}

function pointKey(point: HistoricalReportBillingPoint): string {
  return `${point.cityCode}::${point.billingPointCode}`;
}

function noPeriodPlaceholder(): string {
  if (!hasText(importForm.billingPointCode)) return "请先选择报账点";
  if (importOptionsLoading.value && !periodLoadFinished.value) return "正在加载账期";
  if (hasText(importError.value)) return "账期加载失败";
  if (!periodLoadFinished.value) return "请选择账期";
  return "该报账点暂无缺失报告账期";
}
function removeFile(): void {
  importForm.file = null;
}

function canSubmitImport(): boolean {
  return (
    hasText(importForm.billingPointCode) &&
    hasText(importForm.period) &&
    importForm.file !== null
  );
}

function fileChanged(file: UploadFile): void {
  const raw = file.raw;
  if (raw === undefined) return;
  if (!/\.docx?$/i.test(raw.name)) {
    importError.value = "历史报告只支持 .doc 或 .docx 文件。";
    importForm.file = null;
    return;
  }
  importError.value = "";
  importForm.file = raw;
}

async function importHistorical(): Promise<void> {
  importError.value = "";
  const file = importForm.file;
  if (!canSubmitImport() || file === null) {
    importError.value = "请选择报账点、账期和 Word 文件。";
    return;
  }

  importing.value = true;
  try {
    const imported = await businessApi.reports.importHistorical({
      billingPointCode: importForm.billingPointCode,
      cityCode: importForm.cityCode,
      period: importForm.period,
      file,
    });
    focusedReport.value = imported;
    await loadOptions();
    Object.assign(filters, {
      keyword: "",
      period: "",
      cityCode: cityLocked.value ? (session.currentUser?.city?.code ?? "") : "",
      district: "",
      source: "",
      page: 1,
    });
    await syncRouteAndLoad();
    importSuccessVisible.value = true;
  } catch (error) {
    importError.value = error instanceof Error ? error.message : "历史报告导入失败";
  } finally {
    importing.value = false;
  }
}

function closeImportSuccess(): void {
  importSuccessVisible.value = false;
  importVisible.value = false;
}
async function downloadWord(report: ReportSummary): Promise<void> {
  downloadingId.value = report.id;
  try {
    await triggerBrowserDownload(
      `/api/v1/reports/${encodeURIComponent(report.id)}/word`,
      report.wordFileName,
    );
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Word 下载失败");
    console.error("历史报告 Word 下载失败", { reportId: report.id, error });
  } finally {
    downloadingId.value = "";
  }
}

function exportList(): void {
  if (selectedRows.value.length === 0) {
    ElMessage.warning("请先选择报告");
    return;
  }
  const headers = [
    "报告编号",
    "报账点编码",
    "报账点名称",
    "账期",
    "所属地市",
    "所属区县",
    "实际总耗电量",
    "实际报账金额",
    "超标类型",
    "最大超标比例",
  ];
  const content = [
    headers.join(","),
    ...selectedRows.value.map((row) =>
      [
        row.reportNumber,
        row.billingPointCode,
        row.billingPointName,
        row.period,
        row.city.name,
        row.district ?? "",
        row.actualEnergy ?? "",
        row.actualAmount ?? "",
        row.overLimitType ?? "",
        row.maxRatio ?? "",
      ]
        .map(csvCell)
        .join(","),
    ),
  ].join("\n");
  saveBlob(
    new Blob([`\ufeff${content}`], { type: "text/csv;charset=utf-8" }),
    "历史报告目录数据.csv",
  );
}

function csvCell(value: string | number): string {
  return `"${String(value).replaceAll("\"", "\"\"")}"`;
}

function selectRows(rows: ReportSummary[]): void {
  selectedRows.value = rows;
}

function changePage(page: number): void {
  filters.page = page;
  void syncRouteAndLoad();
}

function changePageSize(size: number): void {
  filters.size = size;
  filters.page = 1;
  void syncRouteAndLoad();
}

async function openDetail(report: ReportSummary): Promise<void> {
  const from = new URL(route.fullPath, window.location.origin);
  from.searchParams.set("scrollLeft", String(currentHorizontalScroll()));
  await router.push({
    name: "report-detail",
    params: { reportId: report.id },
    query: { from: `${from.pathname}${from.search}` },
  });
}

function asReport(row: unknown): ReportSummary {
  return row as ReportSummary;
}

function emptyText(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return "-";
  const text = String(value);
  return text.length > 0 ? text : "-";
}

function formatAmount(value: string | number | null | undefined): string {
  if (value === null || value === undefined || String(value).length === 0) return "-";
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return emptyText(value);
  return `¥${numeric.toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function formatEnergy(value: string | number | null | undefined): string {
  if (value === null || value === undefined || String(value).length === 0) return "-";
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return emptyText(value);
  return numeric.toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function overLimitClass(value: string | null | undefined): string {
  if (value === "多项超标") return "overlimit-multiple";
  if (value === "仅同比超标" || value === "同比超标") return "overlimit-yoy";
  if (value === "仅环比超标" || value === "环比超标") return "overlimit-mom";
  if (value === "仅额定标杆超标" || value === "额定标杆超标") return "overlimit-rated";
  return "overlimit-none";
}

function currentHorizontalScroll(): number {
  const wrapper = tableRef.value?.$el.querySelector(
    ".el-scrollbar__wrap",
  ) as HTMLElement | null;
  return wrapper?.scrollLeft ?? 0;
}

function restoreHorizontalScroll(): void {
  const raw = Number(route.query.scrollLeft ?? 0);
  if (!Number.isFinite(raw) || raw <= 0) return;
  tableRef.value?.setScrollLeft?.(raw);
}

onMounted(async () => {
  await loadOptions();
  await load();
});
</script>

<template>
  <section class="report-filter business-card">
    <label>
      <span>关键字</span>
      <ElInput
        v-model="filters.keyword"
        placeholder="报告编号、报账点编码或报账点名称"
        clearable
      />
    </label>
    <label>
      <span>账期</span>
      <ElSelect v-model="filters.period" placeholder="全部账期" clearable>
        <ElOption
          v-for="period in periodOptions"
          :key="period"
          :label="period"
          :value="period"
        />
      </ElSelect>
    </label>
    <label>
      <span>所属地市</span>
      <ElSelect
        v-model="filters.cityCode"
        :disabled="cityLocked"
        placeholder="全部地市"
        clearable
      >
        <ElOption
          v-for="city in cityOptions"
          :key="city.code"
          :label="city.name"
          :value="city.code"
        />
      </ElSelect>
    </label>
    <label>
      <span>所属区县</span>
      <ElSelect v-model="filters.district" placeholder="全部区县" clearable>
        <ElOption
          v-for="district in districtOptions"
          :key="district"
          :label="district"
          :value="district"
        />
      </ElSelect>
    </label>
    <div class="query-actions">
      <ElButton
        class="query-button"
        type="primary"
        :icon="Search"
        :loading="loading"
        @click="syncRouteAndLoad(true)"
      >
        查询
      </ElButton>
      <ElButton class="reset-button" :icon="Refresh" @click="reset">
        重置
      </ElButton>
    </div>
  </section>

  <div class="business-toolbar report-toolbar">
    <div class="toolbar-left">
      <ElButton
        v-if="canImport"
        type="primary"
        :icon="Upload"
        @click="openImport"
      >
        导入报告
      </ElButton>
      <ElButton :icon="Download" @click="exportList">导出Excel</ElButton>
    </div>
    <span class="report-total">
      共<b class="number-emphasis">{{ pageData?.totalElements ?? 0 }}</b>
      份正式报告
    </span>
  </div>

  <PageState v-if="!pageData && loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="历史报告加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <section v-else class="report-table business-card">
    <ElTable
      ref="tableRef"
      v-loading="loading"
      :data="pageData?.items ?? []"
      height="535"
      table-layout="fixed"
      @selection-change="selectRows"
    >
      <ElTableColumn type="selection" width="48" fixed="left" />
      <ElTableColumn
        prop="reportNumber"
        label="报告编号"
        width="175"
        fixed="left"
      />
      <ElTableColumn
        prop="billingPointCode"
        label="报账点编码"
        width="170"
        fixed="left"
      />
      <ElTableColumn
        prop="billingPointName"
        label="报账点名称"
        width="190"
        fixed="left"
        show-overflow-tooltip
      />
      <ElTableColumn prop="period" label="账期" width="120" />
      <ElTableColumn prop="city.name" label="所属地市" width="120" />
      <ElTableColumn label="所属区县" width="125">
        <template #default="scope">
          {{ emptyText(asReport(scope.row).district) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="实际总耗电量" width="150" align="right">
        <template #default="scope">
          {{ formatEnergy(asReport(scope.row).actualEnergy) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="实际报账金额" width="150" align="right">
        <template #default="scope">
          {{ formatAmount(asReport(scope.row).actualAmount) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="超标类型" width="150">
        <template #default="scope">
          <ElTag
            size="small"
            effect="light"
            :class="overLimitClass(asReport(scope.row).overLimitType)"
          >
            {{ emptyText(asReport(scope.row).overLimitType) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="最大超标比例" width="135" align="right">
        <template #default="scope">
          <span
            :class="{
              'max-ratio-danger': hasText(String(asReport(scope.row).maxRatio ?? '')),
            }"
          >
            {{ formatPercent(asReport(scope.row).maxRatio) }}
          </span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="140" fixed="right" align="center">
        <template #default="scope">
          <ElButton link type="primary" @click="openDetail(asReport(scope.row))">
            查看
          </ElButton>
          <ElButton
            link
            type="danger"
            :loading="downloadingId === asReport(scope.row).id"
            @click="downloadWord(asReport(scope.row))"
          >
            下载Word
          </ElButton>
        </template>
      </ElTableColumn>
      <template #empty>
        <ElEmpty description="暂无正式报告" />
      </template>
    </ElTable>

    <footer>
      <span>{{ range }}</span>
      <ElPagination
        background
        layout="sizes, prev, pager, next"
        :current-page="filters.page"
        :page-size="filters.size"
        :page-sizes="[10, 20, 50]"
        :total="pageData?.totalElements ?? 0"
        @current-change="changePage"
        @size-change="changePageSize"
      />
    </footer>
  </section>

  <ElDialog
    v-model="importVisible"
    title="导入历史报告"
    width="min(720px, calc(100vw - 32px))"
    class="history-import-dialog"
    append-to-body
    align-center
    :close-on-click-modal="false"
  >
    <section class="dialog-section">
      <h3>基本信息</h3>
      <ElForm label-position="top">
        <ElFormItem label="报账点">
          <ElSelect
            v-model="importForm.pointKey"
            class="full-point-select"
            filterable
            remote
            teleported
            popper-class="historical-point-select-popper"
            :remote-method="searchImportPoints"
            :loading="importOptionsLoading"
            placeholder="请选择报账点"
            no-data-text="暂无报账点"
            @change="importPointChanged"
          >
            <ElOption
              v-for="point in historicalPoints"
              :key="`${point.cityCode}-${point.billingPointCode}`"
              :label="pointOptionLabel(point)"
              :value="pointKey(point)"
            >
              <div class="historical-point-option">
                <strong>{{ point.billingPointCode }}</strong>
                <span>{{ point.billingPointName }}</span>
                <em>{{ point.cityName }}</em>
              </div>
            </ElOption>
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="账期">
          <ElSelect
            v-model="importForm.period"
            :disabled="!hasText(importForm.billingPointCode) || historicalPeriods.length === 0"
            :placeholder="noPeriodPlaceholder()"
            no-data-text="该报账点暂无缺失报告账期"
            @change="importPeriodChanged"
          >
            <ElOption
              v-for="candidate in historicalPeriods"
              :key="candidate.billingPointPeriodId"
              :label="periodOptionLabel(candidate)"
              :value="candidate.period"
            />
          </ElSelect>
        </ElFormItem>
      </ElForm>
    </section>

    <section class="dialog-section upload-section">
      <h3>上传文件</h3>
      <ElUpload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".doc,.docx"
        :on-change="fileChanged"
        :on-remove="removeFile"
      >
        <div class="upload-box">
          <span class="file-icon">W</span>
          <div>
            <strong>点击或将 Word 文件拖到此处上传</strong>
            <small>支持 .doc、.docx 格式文件，且只能上传 1 个文件</small>
          </div>
          <ElButton>选择文件</ElButton>
        </div>
      </ElUpload>
    </section>

    <ElAlert
      v-if="importError"
      class="import-error"
      :title="importError"
      type="error"
      :closable="false"
      show-icon
    />

    <template #footer>
      <ElButton @click="importVisible = false">取消</ElButton>
      <ElButton type="primary" :loading="importing" @click="importHistorical">确认导入</ElButton>
    </template>
  </ElDialog>

  <ElDialog
    v-model="importSuccessVisible"
    title="导入成功"
    width="360px"
    append-to-body
    align-center
    :show-close="false"
    :close-on-click-modal="false"
  >
    <p class="success-message">历史报告已导入，列表已刷新。</p>
    <template #footer>
      <ElButton type="primary" @click="closeImportSuccess">确定</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.report-filter {
  display: grid;
  grid-template-columns:
    minmax(min(100%, 280px), 1.8fr) minmax(150px, 0.8fr) minmax(150px, 0.8fr)
    minmax(150px, 0.8fr);
  gap: 12px;
  align-items: end;
  padding: 14px 16px;
  margin-bottom: 12px;
}

.report-filter label {
  display: grid;
  min-width: 0;
  gap: 6px;
  color: #1f2d3d;
  font-size: 13px;
  font-weight: 700;
}

.report-filter :deep(.el-input),
.report-filter :deep(.el-select),
.report-filter :deep(.el-date-editor) {
  width: 100%;
}

.query-button,
.reset-button {
  min-width: 76px;
}

.query-actions {
  display: flex;
  grid-column: 1 / -1;
  gap: 10px;
  justify-content: flex-end;
}

.report-toolbar {
  margin: 10px 0;
}

.toolbar-left {
  display: flex;
  gap: 10px;
  align-items: center;
}

.report-total {
  color: #607089;
  font-size: 14px;
}

.report-table {
  overflow-x: auto;
  overflow-y: hidden;
}

.report-table :deep(.el-table) {
  border-radius: 0;
}

.report-table :deep(.el-table .cell) {
  line-height: 1.45;
  white-space: normal;
  word-break: break-word;
}

.max-ratio-danger {
  color: #e5484d;
  font-weight: 700;
}

.report-table :deep(.el-table__body-wrapper) {
  min-height: 455px;
}

.report-table :deep(.el-table__empty-block) {
  min-height: 430px;
}

.report-table :deep(.el-table__fixed-right .el-table__fixed-body-wrapper),
.report-table :deep(.el-table__fixed .el-table__fixed-body-wrapper) {
  background: #fff;
}

.report-table footer {
  display: flex;
  min-height: 56px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  color: #607089;
  border-top: 1px solid #edf1f5;
}

.overlimit-yoy {
  color: #b86100;
  background: #fff4de;
}

.overlimit-mom {
  color: #2368ad;
  background: #e8f3ff;
}

.overlimit-rated {
  color: #b4232d;
  background: #ffe8eb;
}

.overlimit-multiple {
  color: #8a3ffc;
  background: #f1e8ff;
}

.overlimit-none {
  color: #667486;
  background: #edf1f5;
}

.dialog-section + .dialog-section {
  padding-top: 18px;
  margin-top: 18px;
  border-top: 1px solid #edf1f5;
}

.dialog-section h3 {
  position: relative;
  margin: 0 0 16px;
  padding-left: 12px;
  color: #1f2d3d;
  font-size: 18px;
}

.dialog-section h3::before {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 0;
  width: 4px;
  content: "";
  background: #f5223d;
  border-radius: 4px;
}

.import-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.import-fields :deep(.el-select),
.dialog-section :deep(.el-date-editor) {
  width: 100%;
}

.full-point-select {
  width: 100%;
}

.full-point-select :deep(.el-select__wrapper) {
  min-height: 42px;
  height: auto;
}

.full-point-select :deep(.el-select__selected-item) {
  overflow: visible;
  white-space: normal;
  word-break: break-word;
}

.upload-section :deep(.el-upload),
.upload-section :deep(.el-upload-dragger) {
  width: 100%;
}

.upload-section :deep(.el-upload-dragger) {
  min-height: 126px;
  border: 1px dashed #cbd3dd;
  border-radius: 8px;
  background: #fff;
}

.upload-box {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 18px;
  align-items: center;
  min-height: 126px;
  padding: 0 28px;
  text-align: left;
}

.file-icon {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  color: #f5223d;
  border: 3px solid #f5223d;
  border-radius: 8px;
  font-size: 20px;
  font-weight: 800;
}

.upload-box strong,
.upload-box small {
  display: block;
}

.upload-box strong {
  color: #1f2d3d;
  font-size: 15px;
}

.upload-box small {
  margin-top: 8px;
  color: #607089;
}

.import-error {
  margin-top: 14px;
}

.success-message {
  margin: 0;
  color: #1f2d3d;
  line-height: 1.8;
  text-align: center;
}

:global(.historical-point-select-popper) {
  max-width: min(760px, calc(100vw - 32px));
}

:global(.historical-point-select-popper .el-select-dropdown__item) {
  height: auto;
  min-height: 44px;
  padding: 8px 12px;
  line-height: 1.45;
  white-space: normal;
}

:global(.historical-point-option) {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  align-items: center;
  width: 100%;
  white-space: normal;
  word-break: break-word;
}

:global(.historical-point-option strong) {
  color: #1f2d3d;
  font-weight: 700;
}

:global(.historical-point-option span) {
  color: #344054;
}

:global(.historical-point-option em) {
  color: #667085;
  font-style: normal;
}

@media (width <= 1280px) {
  .report-filter {
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr));
  }

  .query-button,
  .reset-button {
    width: auto;
  }
}

@media (width <= 720px) {
  .report-filter,
  .import-fields,
  .upload-box {
    grid-template-columns: 1fr;
  }

  .report-toolbar,
  .report-table footer,
  .toolbar-left {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .query-actions,
  .query-button,
  .reset-button,
  .toolbar-left,
  .toolbar-left .el-button {
    width: 100%;
  }

  .query-actions {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
