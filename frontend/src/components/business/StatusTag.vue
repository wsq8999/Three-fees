<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{ value: string | null | undefined }>();

const metadata: Record<
  string,
  { label: string; type: "success" | "warning" | "danger" | "info" | "primary" }
> = {
  ACTIVE: { label: "已生效", type: "success" },
  PROCESSING: { label: "处理中", type: "warning" },
  QUEUED: { label: "待处理", type: "info" },
  FAILED: { label: "失败", type: "danger" },
  SUPERSEDED: { label: "已替换", type: "info" },
  NORMAL: { label: "正常", type: "success" },
  OVER_LIMIT: { label: "超标", type: "danger" },
  PENDING_REVIEW: { label: "待稽核", type: "warning" },
  NOT_APPLICABLE: { label: "—", type: "info" },
  NONE: { label: "—", type: "info" },
  DRAFT: { label: "待生成", type: "warning" },
  FINAL: { label: "已生成", type: "success" },
  CORRECTED: { label: "已生成", type: "success" },
  HISTORICAL_IMPORTED: { label: "历史导入", type: "info" },
  APPROVED: { label: "审核通过", type: "success" },
  REJECTED: { label: "审核驳回", type: "danger" },
  PENDING: { label: "待审核", type: "warning" },
  ELIGIBLE: { label: "可报账", type: "success" },
  INELIGIBLE: { label: "不可报账", type: "danger" },
  EDITING: { label: "编辑中", type: "warning" },
  FINALIZED: { label: "已正式生成", type: "success" },
};

const resolved = computed(() =>
  props.value === null || props.value === undefined || props.value.length === 0
    ? { label: "—", type: "info" as const }
    : (metadata[props.value] ?? { label: props.value, type: "info" as const }),
);
</script>

<template>
  <ElTag :type="resolved.type" effect="light" size="small">
    {{ resolved.label }}
  </ElTag>
</template>
