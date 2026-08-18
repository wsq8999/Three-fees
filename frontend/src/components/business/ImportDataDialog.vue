<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { UploadFile, UploadInstance } from "element-plus";

import { businessApi } from "@/api/business-api";
import { ApiProblem } from "@/api/problem-details";
import type { DatasetType, ImportBatch } from "@/types/business";

/**
 * ==============================
 * Props / Emits
 * ==============================
 */

const props = defineProps<{
  modelValue: boolean;
  defaultPeriod?: string;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  imported: [batches: ImportBatch[]];
}>();

/**
 * ==============================
 * 常量
 * ==============================
 */

/**
 * 每隔1秒刷新一次导入进度。
 */
const POLL_INTERVAL_MS = 1_000;

/**
 * 最长自动等待10分钟。
 *
 * 超过10分钟以后不判定导入失败，
 * 只停止自动查询，并提示用户后台任务仍可能继续。
 */
const POLL_TIMEOUT_MS = 10 * 60 * 1_000;

/**
 * 当批次没有出现在列表接口中时，
 * 每轮最多补查5个批次。
 *
 * 防止重新出现一次轮询几十个HTTP请求的问题。
 */
const FALLBACK_GET_LIMIT = 5;

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

/**
 * ==============================
 * 页面状态
 * ==============================
 */

const selectedType =
  ref<DatasetType>("BILLING_POINT");

const selectedFile =
  ref<File | null>(null);

const runningBatch =
  ref<ImportBatch | null>(null);

const runningBatches =
  ref<ImportBatch[]>([]);

const progressVisible =
  ref(false);

const successVisible =
  ref(false);

const pendingFocusBatch =
  ref<ImportBatch | null>(null);

const pendingImportedBatches =
  ref<ImportBatch[]>([]);

const isSubmitting =
  ref(false);

const errorMessage =
  ref("");

const progressIssue =
  ref<{
    type: "warning" | "error";
    message: string;
  } | null>(null);

const uploadRef =
  ref<UploadInstance>();

const uploadKey =
  ref(0);

/**
 * ==============================
 * 计算属性
 * ==============================
 */

const canSubmit = computed(
  () =>
    selectedFile.value !== null &&
    !isSubmitting.value &&
    !progressVisible.value,
);

/**
 * 当前正在跟踪的所有批次。
 */
const trackedBatches = computed(() => {
  if (runningBatches.value.length > 0) {
    return runningBatches.value;
  }

  if (runningBatch.value !== null) {
    return [runningBatch.value];
  }

  return [];
});

/**
 * 失败批次。
 */
const failedBatches = computed(() =>
  trackedBatches.value.filter(
    (batch) =>
      batch.status === "FAILED",
  ),
);

/**
 * 已经完成的批次。
 *
 * ACTIVE = 成功
 * FAILED = 失败
 */
const completedBatches = computed(() =>
  trackedBatches.value.filter(
    (batch) =>
      batch.status === "ACTIVE" ||
      batch.status === "FAILED",
  ),
);

/**
 * 总错误数量。
 */
const totalErrors = computed(() =>
  failedBatches.value.reduce(
    (total, batch) =>
      total + batch.errors.length,
    0,
  ),
);

/**
 * 导入百分比。
 */
const progressPercentage = computed(() => {
  const total =
    trackedBatches.value.length;

  if (total === 0) {
    return isSubmitting.value
      ? 20
      : 0;
  }

  return Math.round(
    (completedBatches.value.length /
      total) *
    100,
  );
});

/**
 * Element Plus 进度条状态。
 */
const progressStatus = computed(() => {
  if (
    failedBatches.value.length > 0
  ) {
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
  const total =
    trackedBatches.value.length;

  if (progressIssue.value !== null) {
    return progressIssue.value.type ===
    "error"
      ? "进度查询异常"
      : "后台仍在处理中";
  }

  if (total === 0) {
    return "正在提交导入任务";
  }

  if (
    failedBatches.value.length > 0 &&
    completedBatches.value.length ===
    total
  ) {
    return failedBatches.value.length ===
    total
      ? "导入失败"
      : "部分账期导入失败";
  }

  if (
    completedBatches.value.length ===
    total
  ) {
    return "导入成功";
  }

  return total > 1
    ? "正在处理多个账期"
    : "正在处理导入任务";
});

/**
 * 用户要求这里只保留：
 *
 * 总批次 xx 个，已完成 xx 个
 */
const progressDetail = computed(() => {
  const total =
    trackedBatches.value.length;

  if (total === 0) {
    return "正在上传并解析文件";
  }

  return `总批次 ${total} 个，已完成 ${completedBatches.value.length} 个`;
});

/**
 * 只有系统处理异常失败，
 * 才允许直接重新提交任务。
 *
 * 文件本身校验错误需要重新选择文件。
 */
const retryableFailedBatches = computed(
  () =>
    failedBatches.value.filter(
      (batch) =>
        batch.errors.length > 0 &&
        batch.errors.every(
          (error) =>
            error.code ===
            "IMPORT_PROCESSING_FAILED",
        ),
    ),
);

const canRetryFailedBatches = computed(
  () =>
    failedBatches.value.length > 0 &&
    retryableFailedBatches.value
      .length ===
    failedBatches.value.length,
);

/**
 * 最多展示8条错误。
 */
const sampledErrors = computed(() =>
  failedBatches.value
    .flatMap((batch) =>
      batch.errors.map((error) => ({
        ...error,
        batchLabel:
          importBatchLabel(batch),
      })),
    )
    .slice(0, 8),
);

const remainingErrorCount = computed(
  () =>
    Math.max(
      0,
      totalErrors.value -
      sampledErrors.value.length,
    ),
);

const requiresNewFile = computed(
  () =>
    failedBatches.value.length > 0 &&
    !canRetryFailedBatches.value,
);

/**
 * ==============================
 * 基础工具方法
 * ==============================
 */

function sleep(
  milliseconds: number,
): Promise<void> {
  return new Promise((resolve) => {
    globalThis.setTimeout(
      resolve,
      milliseconds,
    );
  });
}

function isTerminalBatch(
  batch: ImportBatch,
): boolean {
  return (
    batch.status === "ACTIVE" ||
    batch.status === "FAILED"
  );
}

function importBatchLabel(
  batch: ImportBatch,
): string {
  return `${batch.period} / ${
    batch.cityCode ?? "-"
  }`;
}

/**
 * 保持最初创建时的批次顺序。
 */
function orderedBatches(
  ids: string[],
  batchMap: Map<
    string,
    ImportBatch
  >,
): ImportBatch[] {
  return ids
    .map((id) => batchMap.get(id))
    .filter(
      (
        batch,
      ): batch is ImportBatch =>
        batch !== undefined,
    );
}

/**
 * 同步运行中的批次到页面。
 */
function updateRunningBatches(
  ids: string[],
  batchMap: Map<
    string,
    ImportBatch
  >,
): void {
  const latest =
    orderedBatches(
      ids,
      batchMap,
    );

  runningBatches.value = latest;

  runningBatch.value =
    latest[0] ?? null;
}

/**
 * 将API错误转换成用户能够理解的信息。
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

    return `${prefix}查询失败：${error.message}`;
  }

  if (error instanceof Error) {
    return `${prefix}查询失败：${error.message}`;
  }

  return `${prefix}查询失败，请稍后重试。`;
}

/**
 * ==============================
 * 表单
 * ==============================
 */

function selectDataset(
  datasetType: DatasetType,
): void {
  if (progressVisible.value) {
    return;
  }

  selectedType.value =
    datasetType;
}

function resetForm(): void {
  selectedType.value =
    "BILLING_POINT";

  selectedFile.value = null;

  runningBatch.value = null;

  runningBatches.value = [];

  pendingFocusBatch.value = null;

  pendingImportedBatches.value = [];

  errorMessage.value = "";

  progressIssue.value = null;

  successVisible.value = false;

  uploadRef.value?.clearFiles();

  uploadKey.value += 1;
}

function handleFileChange(
  file: UploadFile,
): void {
  errorMessage.value = "";

  const raw = file.raw;

  if (raw === undefined) {
    return;
  }

  if (
    !/\.(?:xlsx|xls|csv)$/i.test(
      raw.name,
    )
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

/**
 * ==============================
 * 导入轮询
 * ==============================
 */

/**
 * 判断所有批次是否已经结束。
 */
function allBatchesFinished(
  ids: string[],
  batchMap: Map<
    string,
    ImportBatch
  >,
): boolean {
  if (
    batchMap.size < ids.length
  ) {
    return false;
  }

  return ids.every((id) => {
    const batch =
      batchMap.get(id);

    return (
      batch !== undefined &&
      isTerminalBatch(batch)
    );
  });
}

/**
 * 全部批次处理结束后的统一逻辑。
 */
function finishPolling(
  ids: string[],
  batchMap: Map<
    string,
    ImportBatch
  >,
): void {
  updateRunningBatches(
    ids,
    batchMap,
  );

  const latest =
    orderedBatches(
      ids,
      batchMap,
    );

  const succeeded =
    latest.filter(
      (batch) =>
        batch.status === "ACTIVE",
    );

  /**
   * 全部成功才展示成功弹窗。
   *
   * 有任何FAILED时继续留在进度弹窗，
   * 显示失败原因。
   */
  if (
    succeeded.length ===
    latest.length
  ) {
    pendingFocusBatch.value =
      succeeded.find(
        (batch) =>
          batch.datasetType ===
          "BILLING_POINT",
      ) ??
      succeeded[0] ??
      null;

    pendingImportedBatches.value =
      succeeded;

    successVisible.value = true;
  }
}

/**
 * 进度轮询。
 *
 * 新逻辑：
 *
 * 1. 每1秒只调用一次批次列表接口；
 * 2. 列表里已经返回的批次直接更新；
 * 3. 不再每秒对88个批次分别GET；
 * 4. 列表中确实找不到的批次才少量补查；
 * 5. 单批次补查每轮最多5个；
 * 6. 最长自动等待10分钟。
 */
async function pollBatches(
  ids: string[],
): Promise<void> {
  const validIds = [
    ...new Set(
      ids.filter(
        (id) =>
          id !== null &&
          id !== undefined &&
          id.length > 0,
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
   * 先用创建接口返回的数据初始化Map。
   */
  const batchMap =
    new Map<
      string,
      ImportBatch
    >();

  for (const batch of
    runningBatches.value) {
    if (
      validIds.includes(batch.id)
    ) {
      batchMap.set(
        batch.id,
        batch,
      );
    }
  }

  if (
    runningBatch.value !== null &&
    validIds.includes(
      runningBatch.value.id,
    )
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
    Date.now() +
    POLL_TIMEOUT_MS;

  /**
   * 用于超过100条批次时，
   * 对列表接口没有覆盖到的批次做轮转补查。
   */
  let fallbackCursor = 0;

  while (
    Date.now() < deadline
    ) {
    /**
     * 如果当前已知状态已经全部结束，
     * 不再发任何网络请求。
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

    let listResult:
      | ImportBatch[]
      | null = null;

    /**
     * 正常情况下每轮只有这一条请求。
     */
    try {
      listResult =
        await businessApi.imports.list();
    } catch (error) {
      progressIssue.value = {
        type: "error",
        message:
          pollingErrorMessage(
            null,
            error,
          ),
      };

      return;
    }

    const trackedIdSet =
      new Set(validIds);

    const idsSeenInList =
      new Set<string>();

    /**
     * 只取本次导入创建的批次。
     */
    for (const batch of listResult) {
      if (
        !trackedIdSet.has(
          batch.id,
        )
      ) {
        continue;
      }

      idsSeenInList.add(
        batch.id,
      );

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
     * 列表更新以后可能已经全部结束。
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
     * 找出：
     *
     * 1. 尚未结束；
     * 2. 当前列表接口又没有返回；
     *
     * 的批次。
     *
     * 这种情况通常只会在批次数超过列表100条时出现。
     */
    const missingPendingIds =
      validIds.filter((id) => {
        const batch =
          batchMap.get(id);

        if (
          batch !== undefined &&
          isTerminalBatch(batch)
        ) {
          return false;
        }

        return !idsSeenInList.has(
          id,
        );
      });

    /**
     * 对列表没覆盖到的批次进行少量补查。
     *
     * 每轮最多5个，
     * 不允许重新出现一次请求几十个批次的情况。
     */
    if (
      missingPendingIds.length >
      0
    ) {
      const fallbackIds:
        string[] = [];

      const fallbackCount =
        Math.min(
          FALLBACK_GET_LIMIT,
          missingPendingIds.length,
        );

      for (
        let offset = 0;
        offset < fallbackCount;
        offset += 1
      ) {
        const index =
          (fallbackCursor +
            offset) %
          missingPendingIds.length;

        fallbackIds.push(
          missingPendingIds[index],
        );
      }

      fallbackCursor =
        (fallbackCursor +
          fallbackCount) %
        missingPendingIds.length;

      const results =
        await Promise.allSettled(
          fallbackIds.map(
            async (id) => {
              const batch =
                await businessApi.imports.get(
                  id,
                );

              if (
                batch === undefined
              ) {
                throw new Error(
                  "接口未返回批次数据",
                );
              }

              return {
                id,
                batch,
              };
            },
          ),
        );

      for (
        let index = 0;
        index < results.length;
        index += 1
      ) {
        const result =
          results[index];

        const id =
          fallbackIds[index];

        if (
          result.status ===
          "fulfilled"
        ) {
          batchMap.set(
            result.value.id,
            result.value.batch,
          );

          continue;
        }

        /**
         * 如果真的发生403，
         * 这里会明确告诉用户是哪一个批次。
         */
        progressIssue.value = {
          type: "error",
          message:
            pollingErrorMessage(
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

    await sleep(
      POLL_INTERVAL_MS,
    );
  }

  /**
   * 10分钟后只停止自动查询。
   *
   * 不把后台任务标记为失败。
   */
  progressIssue.value = {
    type: "warning",
    message:
      "自动等待已超过10分钟，后台任务可能仍在继续处理。请稍后重新打开页面查看最新结果，不要重复上传同一个文件。",
  };
}

/**
 * ==============================
 * 提交导入
 * ==============================
 */

async function submit(): Promise<void> {
  if (
    selectedFile.value === null
  ) {
    return;
  }

  errorMessage.value = "";

  progressIssue.value = null;

  progressVisible.value = true;

  isSubmitting.value = true;

  try {
    const created =
      await businessApi.imports.create(
        {
          datasetType:
          selectedType.value,

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

    runningBatches.value =
      created;

    runningBatch.value =
      created[0] ?? null;

    await pollBatches(
      created.map(
        (batch) => batch.id,
      ),
    );
  } catch (error) {
    /**
     * 创建导入任务阶段失败，
     * 才关闭进度弹窗并回到主弹窗显示错误。
     */
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
        : error instanceof Error
          ? error.message
          : "导入任务提交失败";
  } finally {
    isSubmitting.value = false;
  }
}

/**
 * ==============================
 * 失败任务重试
 * ==============================
 */

async function retry(): Promise<void> {
  if (
    !canRetryFailedBatches.value
  ) {
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

    runningBatches.value =
      retried;

    runningBatch.value =
      retried[0] ?? null;

    await pollBatches(
      retried.map(
        (batch) => batch.id,
      ),
    );
  } catch (error) {
    progressIssue.value = {
      type: "error",
      message:
        error instanceof ApiProblem &&
        (error.code ===
          "IMPORT_REQUIRES_NEW_FILE" ||
          error.code ===
          "IMPORT_BATCH_NOT_RETRYABLE")
          ? error.detail
          : error instanceof Error
            ? error.message
            : "重试失败",
    };
  } finally {
    isSubmitting.value = false;
  }
}

/**
 * ==============================
 * 成功 / 关闭
 * ==============================
 */

async function confirmSuccess(): Promise<void> {
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

/**
 * ==============================
 * 弹窗打开
 * ==============================
 */

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
  <!-- =========================
       导入主弹窗
       ========================= -->
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
      <div class="form-row type-row">
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
                selectedType ===
                datasetType,
            }"
            :disabled="
              progressVisible
            "
            @click="
              selectDataset(
                datasetType
              )
            "
          >
            <b>
              {{
                String(
                  index + 1
                ).padStart(
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

            <i
              v-if="
                selectedType ===
                datasetType
              "
              aria-hidden="true"
            />
          </button>
        </div>
      </div>

      <!-- 上传文件 -->
      <div class="form-row upload-row">
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
          :disabled="
            progressVisible
          "
          :on-change="
            handleFileChange
          "
          :on-remove="
            () =>
              (selectedFile =
                null)
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
                {{
                  selectedFile.name
                }}
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

      <!-- 主弹窗错误 -->
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

  <!-- =========================
       导入进度弹窗
       ========================= -->
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
      <section
        class="batch-result batch-summary"
      >
        <div>
          <strong>
            {{ progressSummary }}
          </strong>

          <!-- 百分比只在这里显示一次 -->
          <span>
            {{ progressPercentage }}%
          </span>
        </div>

        <!--
          show-text=false：
          进度条右边不再重复显示第二个百分比
        -->
        <ElProgress
          :percentage="
            progressPercentage
          "
          :status="
            progressStatus
          "
          :show-text="false"
        />

        <!--
          用户要求：
          这里只显示总批次、已完成
        -->
        <p>
          {{ progressDetail }}
        </p>
      </section>

      <!-- 轮询本身发生异常 -->
      <ElAlert
        v-if="progressIssue"
        class="import-errors"
        :type="
          progressIssue.type
        "
        :closable="false"
        show-icon
        :title="
          progressIssue.message
        "
      />

      <!-- 文件校验失败 -->
      <ElAlert
        v-if="requiresNewFile"
        class="import-errors"
        type="warning"
        :closable="false"
        show-icon
        title="请修正文件后重新导入，校验失败类任务不能直接重试。"
      />

      <!-- 具体失败内容 -->
      <ElAlert
        v-if="
          failedBatches.length >
          0
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
            sampledErrors.length >
            0
          "
        >
          <li
            v-for="item in sampledErrors"
            :key="`${item.batchLabel}-${item.row}-${item.column}-${item.code ?? ''}`"
          >
            {{
              item.batchLabel
            }}：第
            {{ item.row }}
            行
            {{ item.column }}
            {{ item.code ?? "" }}
            {{ item.message }}
          </li>
        </ul>

        <p
          v-if="
            remainingErrorCount >
            0
          "
        >
          还有
          {{
            remainingErrorCount
          }}
          条错误未展示。
        </p>
      </ElAlert>
    </div>

    <template #footer>
      <!--
        只有系统处理异常才能直接重试。
      -->
      <ElButton
        v-if="
          canRetryFailedBatches
        "
        :loading="
          isSubmitting
        "
        @click="retry"
      >
        重新提交任务
      </ElButton>

      <!--
        有失败、轮询异常或超时时允许关闭进度弹窗。
      -->
      <ElButton
        v-if="
          failedBatches.length >
            0 ||
          progressIssue !== null
        "
        @click="
          closeProgressDialog
        "
      >
        关闭
      </ElButton>
    </template>
  </ElDialog>

  <!-- =========================
       导入成功弹窗
       ========================= -->
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
        @click="
          confirmSuccess
        "
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

/* =========================
   表单行
   ========================= */

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

/* =========================
   导入类型
   ========================= */

.dataset-grid {
  display: grid;
  grid-template-columns:
    repeat(
      2,
      minmax(0, 1fr)
    );
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
  border:
    1px solid #d7e0ee;
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

/* =========================
   文件上传
   ========================= */

.file-upload {
  width: 100%;
}

.selected-file,
.batch-result
> div:first-child {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content:
    space-between;
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

/* =========================
   导入进度
   ========================= */

.batch-result {
  display: grid;
  gap: 10px;
}

.batch-summary p {
  margin: 0;
  color: #5f6f86;
  line-height: 1.7;
}

.batch-summary
> div:first-child
> span {
  color: #5f6f86;
  font-size: 14px;
}

/* =========================
   错误信息
   ========================= */

.import-errors ul {
  padding-left: 18px;
  margin: 0;
}

.import-errors li {
  margin: 6px 0;
  line-height: 1.7;
}

/* =========================
   成功弹窗
   ========================= */

.success-message {
  margin: 0;
  color: #1f2d3d;
  line-height: 1.8;
  text-align: center;
}

/* =========================
   弹窗高度
   ========================= */

:deep(.import-dialog),
:deep(
  .import-progress-dialog
),
:deep(
  .import-success-dialog
) {
  display: flex;
  flex-direction: column;
  max-height:
    calc(100vh - 48px);
  margin: 0 auto;
}

:deep(
  .import-dialog
    .el-dialog__body
),
:deep(
  .import-progress-dialog
    .el-dialog__body
),
:deep(
  .import-success-dialog
    .el-dialog__body
) {
  overflow: auto;
}

/* =========================
   大屏
   ========================= */

@media (
min-width: 1200px
) {
  .dataset-grid {
    grid-template-columns:
      repeat(
        4,
        minmax(0, 1fr)
      );
  }
}

/* =========================
   小屏
   ========================= */

@media (
max-width: 760px
) {
  .form-row {
    grid-template-columns:
      1fr;
  }

  .dataset-grid {
    grid-template-columns:
      1fr;
  }

  .dataset-card,
  .selected-file,
  .batch-result
  > div:first-child {
    align-items:
      flex-start;
    flex-direction: column;
  }
}
</style>
