<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Download, Refresh, Search, Upload } from "@element-plus/icons-vue";
import type { TableInstance, UploadFile, UploadInstance } from "element-plus";
import { ElMessage } from "element-plus";

import { businessApi, formatPercent, saveBlob, triggerBrowserDownload } from "@/api/business-api";
import OverLimitRatioTags from "@/components/business/OverLimitRatioTags.vue";
import OverLimitTypeTags from "@/components/business/OverLimitTypeTags.vue";
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
const reportFilterOptions = ref({ periods: [] as string[], cities: [] as BusinessCity[], districts: [] as string[] });
const allCities = ref<BusinessCity[]>([]);
const historicalPoints = ref<HistoricalReportBillingPoint[]>([]);
const historicalPeriods = ref<HistoricalReportPeriod[]>([]);
const importOptionsLoading = ref(false);
const periodLoadFinished = ref(false);
const importVisible = ref(false);
const importSuccessVisible = ref(false);
const importError = ref("");
const selectedRows = ref<ReportSummary[]>([]);
const tableRef = ref<TableInstance>();
const historicalUploadRef = ref<UploadInstance>();
const historicalUploadKey = ref(0);
const focusedReport = ref<ReportSummary | null>(null);

const importForm = reactive({
  pointKey: "",
  billingPointCode: "",
  cityCode: "",
  period: "",
  file: null as File | null,
});

const filters = reactive({
  reportNumber: String(route.query.reportNumber ?? ""),
  billingPointCode: String(route.query.billingPointCode ?? ""),
  billingPointName: String(route.query.billingPointName ?? ""),
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

const cityOptions = computed<BusinessCity[]>(() => {
  const map = new Map<string, BusinessCity>();
  const currentCity = session.currentUser?.city;
  if (currentCity) map.set(currentCity.code, currentCity);
  for (const city of allCities.value) map.set(city.code, city);
  for (const city of reportFilterOptions.value.cities) {
    const knownCity = map.get(city.code);
    if (knownCity === undefined || knownCity.name === knownCity.code) {
      map.set(city.code, city);
    }
  }
  return Array.from(map.values());
});
const districtOptions = computed(() =>
  reportFilterOptions.value.districts,
);
const selectedHistoricalPointLabel = computed(() => {
  const selected = historicalPoints.value.find((point) => pointKey(point) === importForm.pointKey);
  return selected === undefined ? "" : pointOptionLabel(selected);
});
const range = computed(() => {
  const total = pageData.value?.totalElements ?? 0;
  if (total === 0) return "已显示 0-0 条，共 0 条";
  const start = (filters.page - 1) * filters.size + 1;
  const end = Math.min(filters.page * filters.size, total);
  return `已显示 ${start}-${end} 条，共 ${total} 条`;
});

function hasText(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}


function reportQuery(page = filters.page, size = filters.size) {
  const textFilters = [
    filters.reportNumber.trim(),
    filters.billingPointCode.trim(),
    filters.billingPointName.trim(),
  ].filter((value) => value.length > 0);

  return {
    reportNumber: filters.reportNumber.trim(),
    billingPointCode: filters.billingPointCode.trim(),
    billingPointName: filters.billingPointName.trim(),

    /*
     * 兼容仍使用 keyword 的旧接口：
     * 只有一个文本条件时同步传 keyword；
     * 新接口直接使用上面三个独立条件。
     */
    keyword: textFilters.length === 1 ? textFilters[0] : "",

    cityCode: filters.cityCode,
    district: filters.district,
    period: filters.period,
    source: filters.source,
    page,
    size,
  };
}

async function loadOptions(): Promise<void> {
  const [options, cities] = await Promise.all([
    businessApi.reports.filterOptions({
      reportNumber: filters.reportNumber.trim(),
      billingPointCode: filters.billingPointCode.trim(),
      billingPointName: filters.billingPointName.trim(),
      keyword: "",
      cityCode: filters.cityCode,
      period: filters.period,
      source: filters.source,
    }),
    businessApi.cities.list().catch(() => []),
  ]);
  reportFilterOptions.value = options;
  allCities.value = cities;
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
      ...(filters.reportNumber
        ? { reportNumber: filters.reportNumber }
        : {}),
      ...(filters.billingPointCode
        ? { billingPointCode: filters.billingPointCode }
        : {}),
      ...(filters.billingPointName
        ? { billingPointName: filters.billingPointName }
        : {}),
      ...(filters.period ? { period: filters.period } : {}),
      ...(filters.cityCode ? { city: filters.cityCode } : {}),
      ...(filters.district ? { district: filters.district } : {}),
      page: String(filters.page),
      size: String(filters.size),
    },
  });

  if (resetPage) {
    await loadOptions();
  }
  await load();
}

function reset(): void {
  Object.assign(filters, {
    reportNumber: "",
    billingPointCode: "",
    billingPointName: "",
    period: "",
    cityCode: session.currentUser?.city?.code ?? "",
    district: "",
    source: "",
    page: 1,
  });

  void syncRouteAndLoad(true);
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
  resetHistoricalUpload();
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

function resetHistoricalUpload(): void {
  importForm.file = null;
  historicalUploadRef.value?.clearFiles();
  historicalUploadKey.value += 1;
}

function closeImport(): void {
  resetHistoricalUpload();
  importVisible.value = false;
}

function removeFile(): void {
  resetHistoricalUpload();
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
    resetHistoricalUpload();
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
    Object.assign(filters, {
      reportNumber: "",
      billingPointCode: "",
      billingPointName: "",
      period: "",
      cityCode: cityLocked.value ? (session.currentUser?.city?.code ?? "") : "",
      district: "",
      source: "",
      page: 1,
    });
    await syncRouteAndLoad(true);
    importSuccessVisible.value = true;
  } catch (error) {
    importError.value = error instanceof Error ? error.message : "历史报告导入失败";
  } finally {
    importing.value = false;
  }
}

function closeImportSuccess(): void {
  importSuccessVisible.value = false;
  resetHistoricalUpload();
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
    "超标比例",
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
        (row.overLimitRatios ?? [])
          .map((item) => `${item.label} ${formatPercent(item.ratio)}`)
          .join("；"),
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
  <section
    class="report-filter history-query-bar business-card"
    aria-label="历史报告查询"
  >
    <label class="history-query-item">
      <span>报告编号</span>
      <ElInput
        v-model="filters.reportNumber"
        placeholder="请输入编号"
        clearable
      />
    </label>

    <label class="history-query-item">
      <span>报账点编码</span>
      <ElInput
        v-model="filters.billingPointCode"
        placeholder="请输入编码"
        clearable
      />
    </label>

    <label class="history-query-item">
      <span>报账点名称</span>
      <ElInput
        v-model="filters.billingPointName"
        placeholder="请输入名称"
        clearable
      />
    </label>

    <label class="history-query-item">
      <span>账期</span>
      <ElDatePicker
        v-model="filters.period"
        type="month"
        value-format="YYYY-MM"
        format="YYYY年MM月"
        placeholder="全部账期"
        clearable
      />
    </label>

    <label class="history-query-item">
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

    <label class="history-query-item">
      <span>所属区县</span>
      <ElSelect
        v-model="filters.district"
        placeholder="全部区县"
        clearable
      >
        <ElOption
          v-for="district in districtOptions"
          :key="district"
          :label="district"
          :value="district"
        />
      </ElSelect>
    </label>

    <div class="query-actions history-query-actions">
      <ElButton
        class="query-button"
        type="primary"
        :icon="Search"
        :loading="loading"
        @click="syncRouteAndLoad(true)"
      >
        查询
      </ElButton>

      <ElButton
        class="reset-button"
        :icon="Refresh"
        @click="reset"
      >
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
      table-layout="fixed"
      @selection-change="selectRows"
    >
      <ElTableColumn type="selection" width="48" />
      <ElTableColumn
        prop="reportNumber"
        label="报告编号"
        width="175"
      />
      <ElTableColumn
        prop="billingPointCode"
        label="报账点编码"
        width="170"
      />
      <ElTableColumn
        prop="billingPointName"
        label="报账点名称"
        width="190"
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
          <OverLimitTypeTags
            :ratios="asReport(scope.row).overLimitRatios"
            :fallback="asReport(scope.row).overLimitType"
          />
        </template>
      </ElTableColumn>
      <ElTableColumn label="超标比例" min-width="230">
        <template #default="scope">
          <OverLimitRatioTags :ratios="asReport(scope.row).overLimitRatios" />
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
    width="min(640px, calc(100vw - 24px))"
    class="history-import-dialog"
    append-to-body
    align-center
    :close-on-click-modal="false"
    @closed="resetHistoricalUpload"
  >
    <section class="dialog-section">
      <h3>基本信息</h3>
      <ElForm class="history-import-form" label-position="top">
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
            :title="selectedHistoricalPointLabel || '请选择报账点'"
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
        :key="historicalUploadKey"
        ref="historicalUploadRef"
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
      <ElButton @click="closeImport">取消</ElButton>
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
  display: flex;
  width: 100%;
  gap: 12px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 14px 16px;
  margin-bottom: 12px;
  box-sizing: border-box;

  /*
   * 查询区始终保持一行。
   * 屏幕较窄时优先压缩输入框，不把查询/重置挤到下一行。
   */
  flex-wrap: nowrap;
}

.report-filter-fields {
  display: grid;
  flex: 1 1 auto;
  min-width: 0;

  /*
   * 关键字略宽，另外三个查询框适当缩短。
   * 设置最大宽度后，大屏幕也不会把字段拉得过长。
   */
  grid-template-columns:
    minmax(180px, 420px)
    repeat(3, minmax(110px, 220px));

  gap: 10px;
  align-items: end;
}

.report-filter-fields label {
  display: grid;
  min-width: 0;
  gap: 6px;
  color: #1f2d3d;
  font-size: 14px;
  font-weight: 600;
}

.report-filter-fields :deep(.el-input),
.report-filter-fields :deep(.el-select),
.report-filter-fields :deep(.el-date-editor) {
  width: 100% !important;
  min-width: 0 !important;
}

.report-filter-fields :deep(.el-input__wrapper),
.report-filter-fields :deep(.el-select__wrapper) {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
}

.query-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: nowrap;
  grid-column: auto !important;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  align-self: flex-end;

  /*
   * 按钮区域永远贴在最右侧。
   */
  margin-left: auto;
  white-space: nowrap;
}

.query-actions .el-button,
.query-button,
.reset-button {
  flex: 0 0 auto;
  width: auto;
  min-width: 68px;
  margin-left: 0;
  padding-right: 10px;
  padding-left: 10px;
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

.dialog-section + .dialog-section {
  padding-top: 10px;
  margin-top: 10px;
  border-top: 1px solid #edf1f5;
}

.dialog-section h3 {
  position: relative;
  margin: 0 0 10px;
  padding-left: 12px;
  color: #1f2d3d;
  font-size: 15px;
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

.history-import-dialog :deep(.el-dialog__body) {
  padding-top: 10px;
  padding-bottom: 8px;
  overflow: visible;
}

.history-import-dialog :deep(.el-dialog__header) {
  padding-bottom: 8px;
}

.history-import-dialog :deep(.el-dialog__footer) {
  padding-top: 8px;
}

.history-import-form {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(112px, 0.7fr);
  gap: 10px;
}

.history-import-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.history-import-form :deep(.el-form-item__label) {
  margin-bottom: 4px;
  line-height: 20px;
}

.import-fields :deep(.el-select),
.dialog-section :deep(.el-date-editor) {
  width: 100%;
}

.full-point-select {
  width: 100% !important;
  min-width: 0;
}

.full-point-select :deep(.el-select__wrapper) {
  width: 100%;
  min-width: 0;
  min-height: 36px;
  height: 36px;
  overflow: hidden;
  border-color: #d8e0ea !important;
  box-shadow: 0 0 0 1px #d8e0ea inset !important;
}

.full-point-select :deep(.el-select__wrapper.is-focused),
.full-point-select :deep(.el-select__wrapper.is-hovering) {
  border-color: #9db6cf !important;
  box-shadow: 0 0 0 1px #9db6cf inset !important;
}

.full-point-select :deep(.el-select__selection) {
  display: block;
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.full-point-select :deep(.el-select__selected-item) {
  display: block;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  border: 0 !important;
  outline: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.full-point-select :deep(.el-select__selected-item span),
.full-point-select :deep(.el-select__selected-item div),
.full-point-select :deep(.el-select__input),
.full-point-select :deep(.el-select__placeholder) {
  min-width: 0 !important;
  max-width: 100% !important;
  overflow: hidden !important;
  border: 0 !important;
  outline: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  text-overflow: ellipsis;
  white-space: nowrap !important;
}

.upload-section :deep(.el-upload),
.upload-section :deep(.el-upload-dragger) {
  width: 100%;
}

.upload-section :deep(.el-upload-dragger) {
  min-height: 82px;
  border: 1px dashed #cbd3dd;
  border-radius: 8px;
  background: #fff;
}

.upload-box {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 12px;
  align-items: center;
  min-height: 82px;
  padding: 0 16px;
  text-align: left;
}

.file-icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #f5223d;
  border: 2px solid #f5223d;
  border-radius: 7px;
  font-size: 16px;
  font-weight: 800;
}

.upload-box strong,
.upload-box small {
  display: block;
}

.upload-box strong {
  color: #1f2d3d;
  font-size: 14px;
}

.upload-box small {
  margin-top: 4px;
  color: #607089;
}

.import-error {
  margin-top: 8px;
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

/*
 * 中等宽度：继续保持一行，只缩短字段。
 */
@media (width <= 1180px) {
  .report-filter {
    gap: 10px;
  }

  .report-filter-fields {
    grid-template-columns:
      minmax(150px, 1.45fr)
      repeat(3, minmax(90px, 0.85fr));
    gap: 8px;
  }

  .query-actions {
    gap: 6px;
  }

  .query-actions .el-button,
  .query-button,
  .reset-button {
    min-width: 62px;
    padding-right: 8px;
    padding-left: 8px;
  }
}

/*
 * 你截图中的窄页面宽度也保持一行。
 * 此时进一步压缩四个字段，不再改成两列或单列。
 */
@media (width <= 820px) {
  .report-filter {
    gap: 6px;
    padding: 12px 10px;
  }

  .report-filter-fields {
    grid-template-columns:
      minmax(115px, 1.35fr)
      repeat(3, minmax(64px, 0.72fr));
    gap: 6px;
  }

  .report-filter-fields label {
    gap: 4px;
    font-size: 13px;
  }

  .query-actions {
    gap: 4px;
  }

  .query-actions .el-button,
  .query-button,
  .reset-button {
    min-width: 54px;
    padding-right: 6px;
    padding-left: 6px;
  }
}

/*
 * 极窄宽度仍然不纵向堆叠查询条件。
 * 如果设备宽度实在不足，则允许查询卡片内部横向滚动，
 * 而不是把四个字段变成四行。
 */
@media (width <= 560px) {
  .report-filter {
    overflow-x: auto;
  }

  .report-filter-fields {
    flex: 1 0 360px;
    grid-template-columns:
      minmax(105px, 1.3fr)
      repeat(3, minmax(58px, 0.7fr));
    gap: 5px;
  }

  .query-actions {
    flex: 0 0 auto;
  }

  .query-actions .el-button,
  .query-button,
  .reset-button {
    min-width: 50px;
    padding-right: 5px;
    padding-left: 5px;
  }

  /*
   * 下面这些区域仍可按移动端方式布局；
   * 只是不再影响历史报告查询区。
   */
  .import-fields,
  .history-import-form {
    grid-template-columns: minmax(0, 1.6fr) minmax(90px, 0.72fr);
    gap: 8px;
  }

  .history-import-dialog .upload-box {
    grid-template-columns: auto 1fr;
    gap: 8px;
    padding: 0 10px;
  }

  .history-import-dialog .upload-box small {
    display: none;
  }

  .report-toolbar,
  .report-table footer,
  .toolbar-left {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .toolbar-left,
  .toolbar-left .el-button {
    width: 100%;
  }
}



/* 历史报告查询区：大屏、小屏统一保持一行、六字段等宽 */
.history-query-bar {
  display: grid !important;
  width: 100% !important;
  min-width: 0 !important;
  grid-template-columns: repeat(6, minmax(0, 1fr)) max-content !important;
  column-gap: 10px !important;
  row-gap: 0 !important;
  align-items: end !important;
  padding: 14px 16px !important;
  margin-bottom: 12px !important;
  overflow: visible !important;
  box-sizing: border-box !important;
}

.history-query-item {
  display: grid !important;
  width: 100% !important;
  min-width: 0 !important;
  max-width: none !important;
  grid-template-rows: 22px 42px !important;
  gap: 6px !important;
  margin: 0 !important;
  padding: 0 !important;
  align-items: stretch !important;
  box-sizing: border-box !important;
}

.history-query-item > span {
  display: flex !important;
  width: 100% !important;
  min-width: 0 !important;
  height: 22px !important;
  align-items: center !important;
  color: #1f2d3d !important;
  font-size: 14px !important;
  font-weight: 600 !important;
  line-height: 22px !important;
  white-space: nowrap !important;
}

.history-query-item :deep(.el-input),
.history-query-item :deep(.el-select),
.history-query-item :deep(.el-date-editor) {
  display: block !important;
  width: 100% !important;
  min-width: 0 !important;
  max-width: 100% !important;
  height: 42px !important;
  margin: 0 !important;
  box-sizing: border-box !important;
}

.history-query-item :deep(.el-input__wrapper),
.history-query-item :deep(.el-select__wrapper) {
  width: 100% !important;
  min-width: 0 !important;
  max-width: 100% !important;
  height: 42px !important;
  min-height: 42px !important;
  padding: 0 10px !important;
  box-sizing: border-box !important;
}

.history-query-item :deep(.el-input__inner) {
  width: 100% !important;
  min-width: 0 !important;
  color: #344054 !important;
  font-size: 13px !important;
  font-weight: 400 !important;
  line-height: 40px !important;
  overflow: visible !important;
  text-overflow: clip !important;
  white-space: nowrap !important;
}

.history-query-item :deep(.el-input__inner::placeholder) {
  color: #98a2b3 !important;
  font-size: 13px !important;
  font-weight: 400 !important;
  opacity: 1 !important;
}

.history-query-item :deep(.el-select__selection) {
  width: 100% !important;
  min-width: 0 !important;
}

.history-query-item :deep(.el-select__placeholder),
.history-query-item :deep(.el-select__selected-item) {
  min-width: 0 !important;
  color: #98a2b3 !important;
  font-size: 13px !important;
  font-weight: 400 !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  white-space: nowrap !important;
}

.history-query-actions {
  display: flex !important;
  width: auto !important;
  min-width: max-content !important;
  height: 42px !important;
  gap: 8px !important;
  align-items: center !important;
  justify-content: flex-end !important;
  align-self: end !important;
  margin: 0 !important;
  padding: 0 !important;
  flex-direction: row !important;
  flex-wrap: nowrap !important;
  white-space: nowrap !important;
  box-sizing: border-box !important;
}

.history-query-actions .el-button,
.history-query-actions .query-button,
.history-query-actions .reset-button {
  width: 76px !important;
  min-width: 76px !important;
  height: 42px !important;
  flex: 0 0 76px !important;
  margin: 0 !important;
  padding: 0 10px !important;
  font-size: 14px !important;
  box-sizing: border-box !important;
}


/* 历史报告列表：保留分页和横向宽表，取消列表内部纵向滚动 */
.report-table {
  overflow-x: auto !important;
  overflow-y: visible !important;
}

.report-table :deep(.el-table),
.report-table :deep(.el-table__inner-wrapper),
.report-table :deep(.el-table__body-wrapper) {
  height: auto !important;
  max-height: none !important;
}

.report-table :deep(.el-table__body-wrapper) {
  overflow-y: visible !important;
}

/* 横向滚动继续保留，字段多时仍可左右查看 */
.report-table :deep(.el-scrollbar__wrap) {
  max-height: none !important;
}

</style>
