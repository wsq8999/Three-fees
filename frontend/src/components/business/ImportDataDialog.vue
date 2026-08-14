<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { UploadFile, UploadInstance } from "element-plus";

import { businessApi } from "@/api/business-api";
import { ApiProblem } from "@/api/problem-details";
import type { DatasetType, ImportBatch } from "@/types/business";

const props = defineProps<{ modelValue: boolean; defaultPeriod?: string }>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  imported: [batch: ImportBatch];
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

const period = ref(props.defaultPeriod ?? "");
const selectedType = ref<DatasetType>("BILLING_POINT");
const selectedFile = ref<File | null>(null);
const batches = ref<ImportBatch[]>([]);
const runningBatch = ref<ImportBatch | null>(null);
const runningBatches = ref<ImportBatch[]>([]);
const progressVisible = ref(false);
const successVisible = ref(false);
const pendingFocusBatch = ref<ImportBatch | null>(null);
const isSubmitting = ref(false);
const errorMessage = ref("");
const uploadRef = ref<UploadInstance>();
const uploadKey = ref(0);

const canSubmit = computed(
  () => selectedFile.value !== null && !isSubmitting.value && !progressVisible.value,
);

function selectDataset(datasetType: DatasetType): void {
  if (progressVisible.value) return;
  selectedType.value = datasetType;
}

function statusLabel(status: ImportBatch["status"]): string {
  const labels: Record<ImportBatch["status"], string> = {
    QUEUED: "待处理",
    ACTIVE: "已生效",
    PROCESSING: "处理中",
    FAILED: "失败",
    SUPERSEDED: "已被替换",
  };
  return labels[status] ?? status;
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
    emit("imported", focusBatch);
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
  const failed = runningBatches.value.filter((batch) => batch.status === "FAILED");
  if (failed.length === 0 && runningBatch.value === null) return;
  isSubmitting.value = true;
  progressVisible.value = true;
  try {
    const retried = await Promise.all(
      (failed.length > 0 ? failed : [runningBatch.value as ImportBatch]).map((batch) =>
        businessApi.imports.retry(batch.id),
      ),
    );
    runningBatches.value = retried;
    runningBatch.value = retried[0] ?? null;
    await pollBatches(retried.map((batch) => batch.id));
  } catch (error) {
    progressVisible.value = false;
    errorMessage.value = error instanceof Error ? error.message : "重试失败";
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
    period.value = props.defaultPeriod ?? "";
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
      <div
        v-for="batch in runningBatches.length > 0 ? runningBatches : runningBatch ? [runningBatch] : []"
        :key="batch.id"
        class="batch-result"
      >
        <div>
          <strong>{{ batch.period }} / {{ batch.cityCode ?? "-" }}</strong>
          <span>{{ statusLabel(batch.status) }}</span>
        </div>
        <ElProgress
          :percentage="
            batch.status === 'ACTIVE' || batch.status === 'FAILED'
              ? 100
              : batch.status === 'PROCESSING'
                ? 66
                : 20
          "
          :status="
            batch.status === 'FAILED'
              ? 'exception'
              : batch.status === 'ACTIVE'
                ? 'success'
                : undefined
          "
        />
        <ElAlert
          v-if="batch.status === 'FAILED'"
          class="import-errors"
          type="error"
          :closable="false"
          show-icon
        >
          <ul>
            <li
              v-for="item in batch.errors"
              :key="`${batch.id}-${item.row}-${item.column}-${item.code ?? ''}`"
            >
              第 {{ item.row }} 行 {{ item.column }} {{ item.code ?? "" }} {{ item.message }}
            </li>
          </ul>
        </ElAlert>
      </div>
    </div>
    <template #footer>
      <ElButton
        v-if="runningBatches.some((batch) => batch.status === 'FAILED') || runningBatch?.status === 'FAILED'"
        :loading="isSubmitting"
        @click="retry"
      >
        重新提交任务
      </ElButton>
      <ElButton
        v-if="runningBatches.some((batch) => batch.status === 'FAILED') || runningBatch?.status === 'FAILED'"
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
