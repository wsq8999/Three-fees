<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { UploadFile, UploadInstance } from "element-plus";

import { businessApi } from "@/api/business-api";
import { ApiProblem } from "@/api/problem-details";
import type { DatasetType, ImportBatch } from "@/types/business";

/* =========================================================
 * Props / Emits
 * ========================================================= */

const props = defineProps<{
  modelValue: boolean;
  defaultPeriod?: string;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  imported: [batches: ImportBatch[]];
}>();

/* =========================================================
 * 配置
 * ========================================================= */

/**
 * 每1秒刷新一次批次状态。
 */
const POLL_INTERVAL_MS = 1_000;

/**
 * 最长自动等待10分钟。
 *
 * 超时只停止前端自动轮询，
 * 不把后台任务判定为失败。
 */
const POLL_TIMEOUT_MS = 10 * 60 * 1_000;

/**
 * 如果分页列表中找不到某个本次批次，
 * 少量使用单批次GET补查。
 *
 * 正常情况下不会进入这里。
 */
const MISSING_BATCH_CHECK_LIMIT = 3;

/* =========================================================
 * 导入类型
 * ========================================================= */

const datasetTypes: DatasetType[] = [
  "BILLING_POINT",
  "PAYMENT",
  "METER_READING",
  "BENCHMARK",
];

const datasetMeta: Record<
  DatasetType,
  {
    label: string;
    description: string;
    fieldCount: number;
  }
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

/* =========================================================
 * 页面状态
 * ========================================================= */

const selectedType = ref<DatasetType>("BILLING_POINT");

const selectedFile = ref<File | null>(null);

const runningBatch = ref<ImportBatch | null>(null);

const runningBatches = ref<ImportBatch[]>([]);

const progressVisible = ref(false);

const successVisible = ref(false);

const pendingImportedBatches = ref<ImportBatch[]>([]);

const isSubmitting = ref(false);

const errorMessage = ref("");

const progressIssue = ref<{
  type: "warning" | "error";
  message: string;
} | null>(null);

const uploadRef = ref<UploadInstance>();

const uploadKey = ref(0);

/* =========================================================
 * 计算属性
 * ========================================================= */

const canSubmit = computed(
  () =>
    selectedFile.value !== null &&
    !isSubmitting.value &&
    !progressVisible.value,
);

/**
 * 当前正在跟踪的所有批次。
 */
const trackedBatches = computed<ImportBatch[]>(() => {
  if (runningBatches.value.length > 0) {
    return runningBatches.value;
  }

  if (runningBatch.value !== null) {
    return [runningBatch.value];
  }

  return [];
});

/**
 * 当前是否还处于文件解析阶段。
 *
 * 这个阶段后端还没有返回真正的批次，
 * 所以不能显示真实百分比。
 */
const isParsing = computed(
  () =>
    progressVisible.value &&
    isSubmitting.value &&
    trackedBatches.value.length === 0,
);

/**
 * 判断一个批次是不是已经结束。
 */
function isTerminalBatch(batch: ImportBatch): boolean {
  return (
    batch.status === "ACTIVE" ||
    batch.status === "FAILED" ||
    batch.status === "SUPERSEDED"
  );
}

/**
 * 已完成批次。
 *
 * 成功、失败、被新批次替代，
 * 都属于已经结束处理。
 */
const completedBatches = computed(() =>
  trackedBatches.value.filter(isTerminalBatch),
);


/**
 * 失败批次。
 */
const failedBatches = computed(() =>
  trackedBatches.value.filter(
    (batch) => batch.status === "FAILED",
  ),
);

/**
 * 被后续导入替代的批次。
 */
const supersededBatches = computed(() =>
  trackedBatches.value.filter(
    (batch) => batch.status === "SUPERSEDED",
  ),
);

/**
 * 总错误数量。
 */
const totalErrors = computed(() =>
  failedBatches.value.reduce(
    (total, batch) => total + batch.errors.length,
    0,
  ),
);

/* =========================================================
 * 真实百分比
 * ========================================================= */

/**
 * 不再使用固定20%。
 *
 * 只有真正拿到批次以后，
 * 才计算真实处理进度。
 */
const progressPercentage = computed(() => {
  const total = trackedBatches.value.length;

  if (total === 0) {
    return 0;
  }

  return Math.round(
    (completedBatches.value.length / total) * 100,
  );
});

/**
 * Element Plus进度条状态。
 */
const progressStatus = computed(() => {
  if (failedBatches.value.length > 0) {
    return "exception";
  }

  if (
    trackedBatches.value.length > 0 &&
    completedBatches.value.length ===
    trackedBatches.value.length
  ) {
    return "success";
  }

  return undefined;
});

/**
 * 进度标题。
 */
const progressSummary = computed(() => {
  const total = trackedBatches.value.length;

  if (progressIssue.value !== null) {
    return progressIssue.value.type === "error"
      ? "进度查询异常"
      : "后台仍在处理中";
  }

  if (total === 0) {
    return "正在解析文件";
  }

  if (
    completedBatches.value.length === total &&
    failedBatches.value.length > 0
  ) {
    return failedBatches.value.length === total
      ? "导入失败"
      : "部分账期导入失败";
  }

  if (
    completedBatches.value.length === total &&
    supersededBatches.value.length > 0
  ) {
    return "部分批次已被新的导入替代";
  }

  if (
    completedBatches.value.length === total
  ) {
    return "导入成功";
  }

  return total > 1
    ? "正在处理多个账期"
    : "正在处理导入任务";
});

/**
 * 用户要求：
 *
 * 下方只显示：
 *
 * 总批次 xx 个，已完成 xx 个
 */
const progressDetail = computed(() => {
  const total = trackedBatches.value.length;

  if (total === 0) {
    return "";
  }

  return `总批次 ${total} 个，已完成 ${completedBatches.value.length} 个`;
});

/* =========================================================
 * 失败任务
 * ========================================================= */

const retryableFailedBatches = computed(() =>
  failedBatches.value.filter(
    (batch) =>
      batch.errors.length > 0 &&
      batch.errors.every(
        (error) =>
          error.code === "IMPORT_PROCESSING_FAILED",
      ),
  ),
);

const canRetryFailedBatches = computed(
  () =>
    failedBatches.value.length > 0 &&
    retryableFailedBatches.value.length ===
    failedBatches.value.length,
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
  Math.max(
    0,
    totalErrors.value - sampledErrors.value.length,
  ),
);

const requiresNewFile = computed(
  () =>
    failedBatches.value.length > 0 &&
    !canRetryFailedBatches.value,
);

/* =========================================================
 * 工具方法
 * ========================================================= */

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    globalThis.setTimeout(resolve, milliseconds);
  });
}

function importBatchLabel(batch: ImportBatch): string {
  return `${batch.period} / ${batch.cityCode ?? "-"}`;
}

/**
 * 按创建时原来的顺序返回批次。
 */
function orderedBatches(
  ids: string[],
  batchMap: Map<string, ImportBatch>,
): ImportBatch[] {
  return ids
    .map((id) => batchMap.get(id))
    .filter(
      (batch): batch is ImportBatch =>
        batch !== undefined,
    );
}

/**
 * 同步批次状态到页面。
 */
function updateRunningBatches(
  ids: string[],
  batchMap: Map<string, ImportBatch>,
): void {
  const latest = orderedBatches(ids, batchMap);

  runningBatches.value = latest;
  runningBatch.value = latest[0] ?? null;
}

/**
 * 判断所有批次是否已经结束。
 */
function allBatchesFinished(
  ids: string[],
  batchMap: Map<string, ImportBatch>,
): boolean {
  if (batchMap.size < ids.length) {
    return false;
  }

  return ids.every((id) => {
    const batch = batchMap.get(id);

    return (
      batch !== undefined &&
      isTerminalBatch(batch)
    );
  });
}

/**
 * 把API错误转换成用户看得懂的提示。
 */
function pollingErrorMessage(
  batchId: string | null,
  error: unknown,
): string {
  const prefix =
    batchId === null
      ? "导入进度"
      : `批次 ${batchId}`;

  if (error instanceof ApiProblem) {
    if (
      error.status === 403 ||
      error.code === "ACCESS_DENIED"
    ) {
      return `${prefix}无权访问（403）。请检查当前登录账号权限以及该批次所属城市。`;
    }

    return `${prefix}查询失败：${error.detail || error.message}`;
  }

  if (error instanceof Error) {
    return `${prefix}查询失败：${error.message}`;
  }

  return `${prefix}查询失败，请稍后重试。`;
}

/* =========================================================
 * 表单
 * ========================================================= */

function selectDataset(datasetType: DatasetType): void {
  if (progressVisible.value) {
    return;
  }

  selectedType.value = datasetType;
}

function resetForm(): void {
  selectedType.value = "BILLING_POINT";

  selectedFile.value = null;

  runningBatch.value = null;
  runningBatches.value = [];

  pendingImportedBatches.value = [];

  errorMessage.value = "";
  progressIssue.value = null;

  progressVisible.value = false;
  successVisible.value = false;

  uploadRef.value?.clearFiles();

  uploadKey.value += 1;
}

function handleFileChange(file: UploadFile): void {
  errorMessage.value = "";

  const raw = file.raw;

  if (raw === undefined) {
    return;
  }

  if (
    !/\.(?:xlsx|xls|csv)$/i.test(raw.name)
  ) {
    errorMessage.value =
      "只支持 .xlsx、.xls 或 .csv 文件。";

    selectedFile.value = null;

    uploadRef.value?.clearFiles();

    return;
  }

  if (raw.size === 0) {
    errorMessage.value =
      "不能导入空文件，请重新选择。";

    selectedFile.value = null;

    uploadRef.value?.clearFiles();

    return;
  }

  selectedFile.value = raw;
}

/* =========================================================
 * 批次完成
 * ========================================================= */

function finishPolling(
  ids: string[],
  batchMap: Map<string, ImportBatch>,
): void {
  updateRunningBatches(ids, batchMap);

  const latest = orderedBatches(ids, batchMap);

  const succeeded = latest.filter(
    (batch) => batch.status === "ACTIVE",
  );

  const superseded = latest.filter(
    (batch) => batch.status === "SUPERSEDED",
  );

  /**
   * 全部ACTIVE才算本次导入完整成功。
   */
  if (succeeded.length === latest.length) {
    pendingImportedBatches.value = succeeded;

    successVisible.value = true;

    return;
  }

  /**
   * 如果某些任务被另外一轮导入覆盖，
   * 不再一直卡着。
   */
  if (
    superseded.length > 0 &&
    failedBatches.value.length === 0
  ) {
    progressIssue.value = {
      type: "warning",
      message: `${superseded.length} 个批次已被后续导入替代，请刷新页面查看当前最新数据。`,
    };
  }
}

/* =========================================================
 * 导入进度轮询
 * ========================================================= */

/**
 * 核心逻辑：
 *
 * 本次导入88个：
 * → 通常1页即可
 *
 * 本次导入180个：
 * → 自动读取第0页、第1页
 *
 * 本次导入350个：
 * → 自动读取4页
 *
 * 只要本次ID已经找齐：
 * → 立刻停止继续翻历史页面
 *
 * 不会因为后端每页最大100而丢失第101个以后的任务。
 */
async function pollBatches(
  ids: string[],
  datasetType: DatasetType,
): Promise<void> {
  const validIds = [
    ...new Set(
      ids.filter(
        (id) =>
          id !== null &&
          id !== undefined &&
          id.trim().length > 0,
      ),
    ),
  ];

  if (validIds.length === 0) {
    throw new Error(
      "导入批次创建成功，但响应中缺少批次编号。",
    );
  }

  progressIssue.value = null;

  /**
   * 创建接口返回的数据先放进去，
   * 页面立即能知道总批次数。
   */
  const batchMap =
    new Map<string, ImportBatch>();

  for (const batch of runningBatches.value) {
    if (validIds.includes(batch.id)) {
      batchMap.set(batch.id, batch);
    }
  }

  if (
    runningBatch.value !== null &&
    validIds.includes(runningBatch.value.id)
  ) {
    batchMap.set(
      runningBatch.value.id,
      runningBatch.value,
    );
  }

  updateRunningBatches(
    validIds,
    batchMap,
  );

  const deadline =
    Date.now() + POLL_TIMEOUT_MS;

  while (Date.now() < deadline) {
    /**
     * 已经全部结束，
     * 不再发任何请求。
     */
    if (
      allBatchesFinished(
        validIds,
        batchMap,
      )
    ) {
      finishPolling(
        validIds,
        batchMap,
      );

      return;
    }

    let latest: ImportBatch[];

    try {
      /**
       * 自动分页。
       *
       * 这里只获取本次导入对应的ID。
       */
      latest =
        await businessApi.imports.listTracked(
          validIds,
          datasetType,
        );
    } catch (error) {
      progressIssue.value = {
        type: "error",
        message: pollingErrorMessage(
          null,
          error,
        ),
      };

      return;
    }

    const foundIds =
      new Set<string>();

    for (const batch of latest) {
      foundIds.add(batch.id);

      batchMap.set(
        batch.id,
        batch,
      );
    }

    updateRunningBatches(
      validIds,
      batchMap,
    );

    /**
     * 自动分页回来以后，
     * 再检查一次。
     */
    if (
      allBatchesFinished(
        validIds,
        batchMap,
      )
    ) {
      finishPolling(
        validIds,
        batchMap,
      );

      return;
    }

    /**
     * 理论上本次批次都应该能在分页列表里找到。
     *
     * 如果发现有批次完全没有出现，
     * 少量调用单批次GET进行诊断。
     *
     * 这样如果真的有403，
     * 用户能够知道具体是哪一个批次。
     */
    const missingIds = validIds.filter(
      (id) =>
        !foundIds.has(id) &&
        !(
          batchMap.get(id) !== undefined &&
          isTerminalBatch(
            batchMap.get(id) as ImportBatch,
          )
        ),
    );

    if (missingIds.length > 0) {
      const checkIds = missingIds.slice(
        0,
        MISSING_BATCH_CHECK_LIMIT,
      );

      const results =
        await Promise.allSettled(
          checkIds.map(async (id) => {
            const batch =
              await businessApi.imports.get(id);

            if (batch === undefined) {
              throw new Error(
                "接口未返回批次数据",
              );
            }

            return {
              id,
              batch,
            };
          }),
        );

      for (const [index, result] of results.entries()) {
        const id = checkIds[index];

        if (id === undefined) {
          continue;
        }

        if (result.status === "fulfilled") {
          batchMap.set(
            result.value.id,
            result.value.batch,
          );

          continue;
        }

        progressIssue.value = {
          type: "error",
          message: pollingErrorMessage(
            id,
            result.reason,
          ),
        };

        updateRunningBatches(
          validIds,
          batchMap,
        );

        return;
      }

      updateRunningBatches(
        validIds,
        batchMap,
      );

      if (
        allBatchesFinished(
          validIds,
          batchMap,
        )
      ) {
        finishPolling(
          validIds,
          batchMap,
        );

        return;
      }
    }

    await sleep(POLL_INTERVAL_MS);
  }

  /**
   * 10分钟只是停止前端自动等待。
   *
   * 后端任务仍然可能继续执行，
   * 所以不能提示“导入失败”。
   */
  progressIssue.value = {
    type: "warning",
    message:
      "自动等待已超过10分钟，后台任务可能仍在继续处理。请稍后刷新页面查看最新结果，不要重复上传同一个文件。",
  };
}

/* =========================================================
 * 提交导入
 * ========================================================= */

async function submit(): Promise<void> {
  if (selectedFile.value === null) {
    return;
  }

  errorMessage.value = "";
  progressIssue.value = null;

  /**
   * 先打开弹窗。
   *
   * 这时候还没有批次，
   * 所以页面只显示“正在解析文件”。
   */
  progressVisible.value = true;

  isSubmitting.value = true;

  try {
    const datasetType =
      selectedType.value;

    const created =
      await businessApi.imports.create(
        {
          datasetType,
          fileName:
          selectedFile.value.name,
        },
        selectedFile.value,
      );

    if (created.length === 0) {
      throw new Error(
        "导入接口未返回任何批次。",
      );
    }

    /**
     * 到这里以后才知道真实批次数。
     *
     * 从这里开始显示真实百分比。
     */
    runningBatches.value = created;

    runningBatch.value =
      created[0] ?? null;

    await pollBatches(
      created.map(
        (batch) => batch.id,
      ),
      datasetType,
    );
  } catch (error) {
    progressVisible.value = false;

    errorMessage.value =
      error instanceof ApiProblem &&
      error.fieldErrors.length > 0
        ? `${error.message}: ${error.fieldErrors
          .slice(0, 5)
          .map(
            (item) =>
              `${item.field} ${item.code} ${item.message}`,
          )
          .join("; ")}`
        : error instanceof ApiProblem
          ? error.detail || error.message
          : error instanceof Error
            ? error.message
            : "导入任务提交失败";
  } finally {
    isSubmitting.value = false;
  }
}

/* =========================================================
 * 重试
 * ========================================================= */

async function retry(): Promise<void> {
  if (!canRetryFailedBatches.value) {
    errorMessage.value =
      "请修正文件后重新导入。";

    return;
  }

  isSubmitting.value = true;

  progressVisible.value = true;

  progressIssue.value = null;

  try {
    const retried =
      await Promise.all(
        retryableFailedBatches.value.map(
          (batch) =>
            businessApi.imports.retry(
              batch.id,
            ),
        ),
      );

    runningBatches.value = retried;

    runningBatch.value =
      retried[0] ?? null;

    await pollBatches(
      retried.map(
        (batch) => batch.id,
      ),
      selectedType.value,
    );
  } catch (error) {
    progressIssue.value = {
      type: "error",

      message:
        error instanceof ApiProblem
          ? error.detail || error.message
          : error instanceof Error
            ? error.message
            : "重试失败",
    };
  } finally {
    isSubmitting.value = false;
  }
}

/* =========================================================
 * 成功 / 关闭
 * ========================================================= */

function confirmSuccess(): void {
  const imported =
    pendingImportedBatches.value;

  progressVisible.value = false;

  successVisible.value = false;

  emit(
    "update:modelValue",
    false,
  );

  resetForm();

  if (imported.length > 0) {
    emit(
      "imported",
      imported,
    );
  }
}

function closeProgressDialog(): void {
  progressVisible.value = false;
}

function closeMainDialog(): void {
  emit(
    "update:modelValue",
    false,
  );

  resetForm();
}

/* =========================================================
 * 弹窗打开
 * ========================================================= */

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return;
    }

    resetForm();
  },
);
</script>

<template>
  <!-- =====================================================
       导入数据
       ===================================================== -->
  <ElDialog
    :model-value="modelValue"
    title="导入数据"
    width="min(1040px, calc(100vw - 32px))"
    class="import-dialog"
    append-to-body
    align-center
    :close-on-click-modal="false"
    @update:model-value="
      emit('update:modelValue', $event)
    "
  >
    <div class="import-form">
      <!-- 导入类型 -->
      <div class="form-row">
        <strong>
          <span>*</span>
          导入类型
        </strong>

        <div class="dataset-grid">
          <button
            v-for="(
              datasetType,
              index
            ) in datasetTypes"
            :key="datasetType"
            type="button"
            class="dataset-card"
            :class="{
              selected:
                selectedType === datasetType,
            }"
            :disabled="progressVisible"
            @click="
              selectDataset(datasetType)
            "
          >
            <b>
              {{
                String(index + 1).padStart(
                  2,
                  "0"
                )
              }}
            </b>

            <span>
              <strong>
                {{
                  datasetMeta[
                    datasetType
                    ].label
                }}
              </strong>

              <small>
                {{
                  datasetMeta[
                    datasetType
                    ].description
                }}
                /
                {{
                  datasetMeta[
                    datasetType
                    ].fieldCount
                }}
                列
              </small>
            </span>
          </button>
        </div>
      </div>

      <!-- 上传文件 -->
      <div class="form-row">
        <strong>
          <span>*</span>
          上传文件
        </strong>

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
          :on-remove="
            () => {
              selectedFile = null;
            }
          "
        >
          <div
            v-if="selectedFile"
            class="selected-file"
          >
            <b>
              {{
                selectedFile.name
                  .split(".")
                  .pop()
                  ?.toUpperCase()
              }}
            </b>

            <span>
              <strong>
                {{ selectedFile.name }}
              </strong>

              <small>
                {{
                  (
                    selectedFile.size /
                    1024
                  ).toFixed(1)
                }}
                KB
              </small>
            </span>
          </div>

          <template v-else>
            <strong>
              点击或将文件拖到此处
            </strong>

            <small>
              支持
              .xlsx、.xls、.csv；一次只选择一个文件
            </small>
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
      <ElButton
        @click="closeMainDialog"
      >
        取消
      </ElButton>

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

  <!-- =====================================================
       导入进度
       ===================================================== -->
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
      <!-- ================================================
           阶段1：文件解析
           不显示假百分比
           ================================================ -->
      <section
        v-if="isParsing"
        class="parsing-state"
      >
        <div
          class="parsing-spinner"
          aria-hidden="true"
        />

        <strong>
          正在解析文件
        </strong>

        <p>
          正在读取并校验文件内容，请稍候...
        </p>
      </section>

      <!-- ================================================
           阶段2：真实导入进度
           ================================================ -->
      <template v-else>
        <section class="batch-summary">
          <div class="progress-header">
            <strong>
              {{ progressSummary }}
            </strong>

            <span>
              {{ progressPercentage }}%
            </span>
          </div>

          <!--
            Element Plus自带的文字关闭，
            所以百分比只显示一次。
          -->
          <ElProgress
            :percentage="progressPercentage"
            :status="progressStatus"
            :show-text="false"
          />

          <p v-if="progressDetail">
            {{ progressDetail }}
          </p>
        </section>

        <!-- 轮询异常 -->
        <ElAlert
          v-if="progressIssue"
          class="import-errors"
          :type="progressIssue.type"
          :closable="false"
          show-icon
          :title="progressIssue.message"
        />

        <!-- 文件本身需要修正 -->
        <ElAlert
          v-if="requiresNewFile"
          class="import-errors"
          type="warning"
          :closable="false"
          show-icon
          title="请修正文件后重新导入，校验失败类任务不能直接重试。"
        />

        <!-- 失败详情 -->
        <ElAlert
          v-if="
            failedBatches.length > 0
          "
          class="import-errors"
          type="error"
          :closable="false"
          show-icon
        >
          <p>
            {{
              failedBatches.length
            }}
            个任务失败，共
            {{ totalErrors }}
            条错误。
          </p>

          <ul
            v-if="
              sampledErrors.length > 0
            "
          >
            <li
              v-for="item in sampledErrors"
              :key="`${item.batchLabel}-${item.row}-${item.column}-${item.code ?? ''}`"
            >
              {{ item.batchLabel }}：
              第 {{ item.row }} 行
              {{ item.column }}
              {{ item.code ?? "" }}
              {{ item.message }}
            </li>
          </ul>

          <p
            v-if="
              remainingErrorCount > 0
            "
          >
            还有
            {{
              remainingErrorCount
            }}
            条错误未展示。
          </p>
        </ElAlert>
      </template>
    </div>

    <template #footer>
      <ElButton
        v-if="
          !isParsing &&
          canRetryFailedBatches
        "
        :loading="isSubmitting"
        @click="retry"
      >
        重新提交任务
      </ElButton>

      <ElButton
        v-if="
          !isParsing &&
          (
            failedBatches.length > 0 ||
            progressIssue !== null
          )
        "
        @click="closeProgressDialog"
      >
        关闭
      </ElButton>
    </template>
  </ElDialog>

  <!-- =====================================================
       导入成功
       ===================================================== -->
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
    <p class="success-message">
      导入数据已成功生效。
    </p>

    <template #footer>
      <ElButton
        type="primary"
        @click="confirmSuccess"
      >
        确定
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.import-form,
.progress-list {
  display: grid;
  gap: 18px;
}

/* =========================================================
 * 表单
 * ========================================================= */

.form-row {
  display: grid;
  grid-template-columns:
    minmax(96px, 112px)
    minmax(0, 1fr);
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

/* =========================================================
 * 导入类型
 * ========================================================= */

.dataset-grid {
  display: grid;
  grid-template-columns:
    repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.dataset-card {
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
  flex: 0 0 42px;
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

/* =========================================================
 * 上传
 * ========================================================= */

.file-upload {
  width: 100%;
}

.selected-file {
  display: flex;
  gap: 12px;
  align-items: center;
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

.selected-file span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}

/* =========================================================
 * 文件解析阶段
 * ========================================================= */

.parsing-state {
  display: flex;
  min-height: 170px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px 12px;
  text-align: center;
}

.parsing-spinner {
  width: 34px;
  height: 34px;
  margin-bottom: 18px;
  border: 3px solid #e6ebf2;
  border-top-color: #409eff;
  border-radius: 50%;
  animation: parsing-rotate 0.85s linear infinite;
}

.parsing-state strong {
  color: #1f2d3d;
  font-size: 15px;
}

.parsing-state p {
  margin: 8px 0 0;
  color: #6b7a90;
  font-size: 13px;
  line-height: 1.7;
}

@keyframes parsing-rotate {
  from {
    transform: rotate(0deg);
  }

  to {
    transform: rotate(360deg);
  }
}

/* =========================================================
 * 真实进度
 * ========================================================= */

.batch-summary {
  display: grid;
  gap: 10px;
}

.progress-header {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.progress-header span {
  color: #5f6f86;
  font-size: 14px;
}

.batch-summary p {
  margin: 0;
  color: #5f6f86;
  line-height: 1.7;
}

/* =========================================================
 * 错误
 * ========================================================= */

.import-errors ul {
  padding-left: 18px;
  margin: 0;
}

.import-errors li {
  margin: 6px 0;
  line-height: 1.7;
}

/* =========================================================
 * 成功
 * ========================================================= */

.success-message {
  margin: 0;
  color: #1f2d3d;
  line-height: 1.8;
  text-align: center;
}

/* =========================================================
 * Dialog
 * ========================================================= */

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

/* =========================================================
 * 大屏
 * ========================================================= */

@media (min-width: 1200px) {
  .dataset-grid {
    grid-template-columns:
      repeat(4, minmax(0, 1fr));
  }
}

/* =========================================================
 * 小屏
 * ========================================================= */

@media (max-width: 760px) {
  .form-row {
    grid-template-columns: 1fr;
  }

  .dataset-grid {
    grid-template-columns: 1fr;
  }

  .dataset-card,
  .selected-file,
  .progress-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
