<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { UploadFile, UploadInstance } from "element-plus";

import { businessApi } from "@/api/business-api";
import { ApiProblem } from "@/api/problem-details";
import type { DatasetType, ImportBatch } from "@/types/business";

const props = defineProps<{ modelValue: boolean; defaultPeriod?: string }>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  imported: [batches: ImportBatch[]];
}>();

const datasetTypes: DatasetType[] = [
  "BILLING_POINT",
  "PAYMENT",
  "METER_READING",
  "BENCHMARK",
];

const datasetMeta: Record<
  DatasetType,
  { label: string; description: string; fieldCount: number }
> = {
  BILLING_POINT: {
    label: "报账点清单",
    description: "基础报账点和归属关系",
    fieldCount: 73,
  },
  PAYMENT: {
    label: "缴费明细",
    description: "缴费单、审核与金额信息",
    fieldCount: 198,
  },
  METER_READING: {
    label: "电表读数",
    description: "电表抄表与分摊读数",
    fieldCount: 42,
  },
  BENCHMARK: {
    label: "标杆值",
    description: "额定功率标杆数据",
    fieldCount: 39,
  },
};

const selectedType = ref<DatasetType>("BILLING_POINT");
const selectedFile = ref<File | null>(null);
const batches = ref<ImportBatch[]>([]);
const runningBatch = ref<ImportBatch | null>(null);
const runningBatches = ref<ImportBatch[]>([]);
const progressVisible = ref(false);
const successVisible = ref(false);
const pendingFocusBatch = ref<ImportBatch | null>(null);
const pendingImportedBatches = ref<ImportBatch[]>([]);
const isSubmitting = ref(false);
const errorMessage = ref("");
const uploadRef = ref<UploadInstance>();
const uploadKey = ref(0);

const canSubmit = computed(
  () => selectedFile.value !== null && !isSubmitting.value && !progressVisible.value,
);
const trackedBatches = computed(() =>
  runningBatches.value.length > 0
    ? runningBatches.value
    : runningBatch.value === null
      ? []
      : [runningBatch.value],
);
const failedBatches = computed(() =>
  trackedBatches.value.filter((batch) => batch.status === "FAILED"),
);
const completedBatches = computed(() =>
  trackedBatches.value.filter((batch) => batch.status === "ACTIVE" || batch.status === "FAILED"),
);
const succeededBatches = computed(() =>
  trackedBatches.value.filter((batch) => batch.status === "ACTIVE"),
);
const totalErrors = computed(() =>
  failedBatches.value.reduce((total, batch) => total + batch.errors.length, 0),
);
const totalRows = computed(() =>
  trackedBatches.value.reduce((total, batch) => total + batch.rowCount, 0),
);
const progressPercentage = computed(() => {
  if (trackedBatches.value.length === 0) return isSubmitting.value ? 20 : 0;
  return Math.round((completedBatches.value.length / trackedBatches.value.length) * 100);
});
const progressStatus = computed(() => {
  if (failedBatches.value.length > 0) return "exception";
  if (
    trackedBatches.value.length > 0 &&
    completedBatches.value.length === trackedBatches.value.length
  ) {
    return "success";
  }
  return undefined;
});
const progressSummary = computed(() => {
  const total = trackedBatches.value.length;
  if (total === 0) return "正在提交导入任务";
  if (failedBatches.value.length > 0 && completedBatches.value.length === total) {
    return failedBatches.value.length === total ? "导入失败" : "部分账期导入失败";
  }
  if (completedBatches.value.length === total) return "导入成功";
  return total > 1 ? "正在处理多个账期" : "正在处理导入任务";
});
const progressDetail = computed(() => {
  const total = trackedBatches.value.length;
  if (total === 0) return "正在上传并解析文件";
  const parts = [
    `总批次 ${total} 个`,
    `已完成 ${completedBatches.value.length} 个`,
    `成功 ${succeededBatches.value.length} 个`,
  ];
  if (failedBatches.value.length > 0) parts.push(`失败 ${failedBatches.value.length} 个`);
  if (totalRows.value > 0) parts.push(`总行数 ${totalRows.value}`);
  if (totalErrors.value > 0) parts.push(`错误 ${totalErrors.value} 条`);
  return parts.join("，");
});
const retryableFailedBatches = computed(() =>
  failedBatches.value.filter(
    (batch) =>
      batch.errors.length > 0 &&
      batch.errors.every((error) => error.code === "IMPORT_PROCESSING_FAILED"),
  ),
);
const canRetryFailedBatches = computed(
  () =>
    failedBatches.value.length > 0 &&
    retryableFailedBatches.value.length === failedBatches.value.length,
);
const sampledErrors = computed(() =>
  failedBatches.value
    .flatMap((batch) =>
      batch.errors.map((error) => ({
        ...error,
        batchLabel: importBatchLabel(batch),
      })),
    )
    .slice(0, 8),
);
const remainingErrorCount = computed(() =>
  Math.max(0, totalErrors.value - sampledErrors.value.length),
);
const requiresNewFile = computed(
  () => failedBatches.value.length > 0 && !canRetryFailedBatches.value,
);
function importBatchLabel(batch: ImportBatch): string {
  return `${batch.period} / ${batch.cityCode ?? "-"}`;
}

function selectDataset(datasetType: DatasetType): void {
  if (progressVisible.value) return;
  selectedType.value = datasetType;
}

async function loadBatches(): Promise<void> {
  batches.value = await businessApi.imports.list();
}

function resetForm(): void {
  selectedType.value = "BILLING_POINT";
  selectedFile.value = null;
  runningBatch.value = null;
  runningBatches.value = [];
  pendingFocusBatch.value = null;
  pendingImportedBatches.value = [];
  errorMessage.value = "";
  uploadRef.value?.clearFiles();
  uploadKey.value += 1;
}

function handleFileChange(file: UploadFile): void {
  errorMessage.value = "";
  const raw = file.raw;
  if (raw === undefined) return;
  if (!/\.(?:xlsx|xls|csv)$/i.test(raw.name)) {
    errorMessage.value = "只支持 .xlsx、.xls 或 .csv 文件。";
    selectedFile.value = null;
    uploadRef.value?.clearFiles();
    return;
  }
  if (raw.size === 0) {
    errorMessage.value = "不能导入空文件，请重新选择。";
    selectedFile.value = null;
    uploadRef.value?.clearFiles();
    return;
  }
  selectedFile.value = raw;
}

async function pollBatches(ids: string[]): Promise<void> {
  const validIds = ids.filter(Boolean);
  if (validIds.length === 0) {
    throw new Error("导入批次创建成功，但响应中缺少批次编号。");
  }
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const latestList = (
      await Promise.all(validIds.map((id) => businessApi.imports.get(id)))
    ).filter((item): item is ImportBatch => item !== undefined);
    runningBatches.value = latestList;
    runningBatch.value = latestList[0] ?? null;
    if (
      latestList.length === validIds.length &&
      latestList.every((batch) => batch.status === "ACTIVE" || batch.status === "FAILED")
    ) {
      await loadBatches();
      const succeeded = latestList.filter((batch) => batch.status === "ACTIVE");
      if (succeeded.length === latestList.length) {
        pendingFocusBatch.value =
          succeeded.find((batch) => batch.datasetType === "BILLING_POINT") ?? succeeded[0] ?? null;
        pendingImportedBatches.value = succeeded;
        successVisible.value = true;
      }
      return;
    }
    await new Promise((resolve) => globalThis.setTimeout(resolve, 500));
  }
  errorMessage.value = "导入任务仍在后台处理中，请稍后刷新查看。";
}

async function confirmSuccess(): Promise<void> {
  const focusBatch = pendingFocusBatch.value;
  progressVisible.value = false;
  successVisible.value = false;
  emit("update:modelValue", false);
  resetForm();
  await loadBatches();
  if (focusBatch) {
    emit("imported", pendingImportedBatches.value.length > 0 ? pendingImportedBatches.value : [focusBatch]);
  }
}

async function submit(): Promise<void> {
  if (selectedFile.value === null) return;
  errorMessage.value = "";
  progressVisible.value = true;
  isSubmitting.value = true;
  try {
    const created = await businessApi.imports.create(
      {
        datasetType: selectedType.value,
        fileName: selectedFile.value.name,
      },
      selectedFile.value,
    );
    runningBatches.value = created;
    runningBatch.value = created[0] ?? null;
    await pollBatches(created.map((batch) => batch.id));
  } catch (error) {
    progressVisible.value = false;
    errorMessage.value =
      error instanceof ApiProblem && error.fieldErrors.length > 0
        ? `${error.message}: ${error.fieldErrors
            .slice(0, 5)
            .map((item) => `${item.field} ${item.code} ${item.message}`)
            .join("; ")}`
        : error instanceof Error
          ? error.message
          : "导入任务提交失败";
  } finally {
    isSubmitting.value = false;
  }
}

async function retry(): Promise<void> {
  if (!canRetryFailedBatches.value) {
    errorMessage.value = "请修正文件后重新导入。";
    return;
  }
  isSubmitting.value = true;
  progressVisible.value = true;
  try {
    const retried = await Promise.all(
      retryableFailedBatches.value.map((batch) => businessApi.imports.retry(batch.id)),
    );
    runningBatches.value = retried;
    runningBatch.value = retried[0] ?? null;
    await pollBatches(retried.map((batch) => batch.id));
  } catch (error) {
    progressVisible.value = false;
    errorMessage.value =
      error instanceof ApiProblem &&
      (error.code === "IMPORT_REQUIRES_NEW_FILE" ||
        error.code === "IMPORT_BATCH_NOT_RETRYABLE")
        ? error.detail
        : error instanceof Error
          ? error.message
          : "重试失败";
  } finally {
    isSubmitting.value = false;
  }
}

function closeMainDialog(): void {
  emit("update:modelValue", false);
  resetForm();
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return;
    resetForm();
    void loadBatches();
  },
);
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    title="导入数据"
    width="min(1040px, calc(100vw - 32px))"
    class="import-dialog"
    append-to-body
    align-center
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="import-form">
      <div class="form-row type-row">
        <strong><span>*</span> 导入类型</strong>
        <div class="dataset-grid">
          <button
            v-for="(datasetType, index) in datasetTypes"
            :key="datasetType"
            type="button"
            class="dataset-card"
            :class="{ selected: selectedType === datasetType }"
            :disabled="progressVisible"
            @click="selectDataset(datasetType)"
          >
            <b>{{ String(index + 1).padStart(2, "0") }}</b>
            <span>
              <strong>{{ datasetMeta[datasetType].label }}</strong>
              <small>
                {{ datasetMeta[datasetType].description }} /
                {{ datasetMeta[datasetType].fieldCount }} 列
              </small>
            </span>
            <i v-if="selectedType === datasetType" aria-hidden="true" />
          </button>
        </div>
      </div>

      <div class="form-row upload-row">
        <strong><span>*</span> 上传文件</strong>
        <ElUpload
          :key="uploadKey"
          ref="uploadRef"
          class="file-upload"
          drag
          :auto-upload="false"
          :limit="1"
          accept=".xlsx,.xls,.csv"
          :disabled="progressVisible"
          :on-change="handleFileChange"
          :on-remove="() => (selectedFile = null)"
        >
          <div v-if="selectedFile" class="selected-file">
            <b>{{ selectedFile.name.split(".").pop()?.toUpperCase() }}</b>
            <span>
              <strong>{{ selectedFile.name }}</strong>
              <small>{{ (selectedFile.size / 1024).toFixed(1) }} KB</small>
            </span>
          </div>
          <template v-else>
            <strong>点击或将文件拖到此处</strong>
            <small>支持 .xlsx、.xls、.csv；一次只选择一个文件</small>
          </template>
        </ElUpload>
      </div>

      <ElAlert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      />
    </div>

    <template #footer>
      <ElButton @click="closeMainDialog">取消</ElButton>
      <ElButton
        type="primary"
        :loading="isSubmitting"
        :disabled="!canSubmit"
        @click="submit"
      >
        确认导入
      </ElButton>
    </template>
  </ElDialog>

  <ElDialog
    v-model="progressVisible"
    title="导入进度"
    width="min(560px, calc(100vw - 32px))"
    class="import-progress-dialog"
    append-to-body
    align-center
    :close-on-click-modal="false"
    :show-close="false"
  >
    <div class="progress-list">
      <section class="batch-result batch-summary">
        <div>
          <strong>{{ progressSummary }}</strong>
          <span>{{ progressPercentage }}%</span>
        </div>
        <ElProgress
          :percentage="progressPercentage"
          :status="progressStatus"
        />
        <p>{{ progressDetail }}</p>
      </section>

      <ElAlert
        v-if="requiresNewFile"
        class="import-errors"
        type="warning"
        :closable="false"
        show-icon
        title="请修正文件后重新导入，校验失败类任务不能直接重试。"
      />

      <ElAlert
        v-if="failedBatches.length > 0"
        class="import-errors"
        type="error"
        :closable="false"
        show-icon
      >
        <p>
          {{ failedBatches.length }} 个任务失败，共 {{ totalErrors }} 条错误。
        </p>
        <ul v-if="sampledErrors.length > 0">
          <li
            v-for="item in sampledErrors"
            :key="`${item.batchLabel}-${item.row}-${item.column}-${item.code ?? ''}`"
          >
            {{ item.batchLabel }}：第 {{ item.row }} 行 {{ item.column }}
            {{ item.code ?? "" }} {{ item.message }}
          </li>
        </ul>
        <p v-if="remainingErrorCount > 0">
          还有 {{ remainingErrorCount }} 条错误未展示。
        </p>
      </ElAlert>
    </div>
    <template #footer>
      <ElButton
        v-if="canRetryFailedBatches"
        :loading="isSubmitting"
        @click="retry"
      >
        重新提交任务
      </ElButton>
      <ElButton
        v-if="failedBatches.length > 0"
        @click="progressVisible = false"
      >
        关闭
      </ElButton>
    </template>
  </ElDialog>

  <ElDialog
    v-model="successVisible"
    title="导入成功"
    width="min(420px, calc(100vw - 32px))"
    class="import-success-dialog"
    append-to-body
    align-center
    :close-on-click-modal="false"
    :show-close="false"
  >
    <p class="success-message">导入数据已成功生效。</p>
    <template #footer>
      <ElButton type="primary" @click="confirmSuccess">确定</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.import-form,
.progress-list {
  display: grid;
  gap: 18px;
}

.form-row {
  display: grid;
  grid-template-columns: minmax(96px, 112px) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.form-row > strong {
  padding-top: 11px;
  color: #1f2d3d;
  font-size: 14px;
}

.form-row > strong span {
  color: #ff3152;
}

.dataset-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.dataset-card {
  position: relative;
  display: flex;
  min-height: 116px;
  gap: 14px;
  align-items: center;
  padding: 26px 28px;
  color: #1f2d3d;
  text-align: left;
  background: #fff;
  border: 1px solid #d7e0ee;
  border-radius: 8px;
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    background 0.16s ease;
}

.dataset-card:hover {
  background: #f8fbff;
  border-color: #9db6cf;
}

.dataset-card.selected {
  background: #fff5f7;
  border-color: #ff3152;
}

.dataset-card:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

.dataset-card > b {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  color: #ff3152;
  background: #fff0f3;
  border-radius: 8px;
}

.dataset-card > span {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 6px;
}

.dataset-card small,
.selected-file small {
  color: #6b7a90;
}

.file-upload {
  width: 100%;
}

.selected-file,
.batch-result > div:first-child {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.selected-file b {
  display: grid;
  min-width: 56px;
  height: 42px;
  place-items: center;
  color: #ff3152;
  background: #fff0f3;
  border-radius: 8px;
}

.batch-result {
  display: grid;
  gap: 10px;
}

.batch-summary p {
  margin: 0;
  color: #5f6f86;
  line-height: 1.7;
}

.import-errors ul {
  padding-left: 18px;
  margin: 0;
}

.success-message {
  margin: 0;
  color: #1f2d3d;
  line-height: 1.8;
  text-align: center;
}

:deep(.import-dialog),
:deep(.import-progress-dialog),
:deep(.import-success-dialog) {
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 48px);
  margin: 0 auto;
}

:deep(.import-dialog .el-dialog__body),
:deep(.import-progress-dialog .el-dialog__body),
:deep(.import-success-dialog .el-dialog__body) {
  overflow: auto;
}

@media (min-width: 1200px) {
  .dataset-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .form-row {
    grid-template-columns: 1fr;
  }

  .dataset-grid {
    grid-template-columns: 1fr;
  }

  .dataset-card,
  .selected-file,
  .batch-result > div:first-child {
    align-items: flex-start;
    flex-direction: column;
  }

}
</style>
