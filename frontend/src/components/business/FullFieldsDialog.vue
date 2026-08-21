<script setup lang="ts">
import { computed, ref, watch } from "vue";

import type { BusinessField, FieldGroup } from "@/types/business";

const props = defineProps<{
  modelValue: boolean;
  dataLabel: string;
  expectedCount: number;
  summary: BusinessField[];
  groups: FieldGroup[];
}>();

defineEmits<{ "update:modelValue": [value: boolean] }>();

const keyword = ref("");
const currentPage = ref(1);
const pageSize = ref(5);
const fields = computed(() => props.groups.flatMap((group) => group.fields));
const displayFields = computed(() => {
  if (fields.value.length >= props.expectedCount) return fields.value;
  const padding = Array.from(
    { length: props.expectedCount - fields.value.length },
    (_, index): BusinessField => ({
      key: `catalog-pending-${index + fields.value.length + 1}`,
      label: `源字段 ${index + fields.value.length + 1}`,
      value: "—",
    }),
  );
  return [...fields.value, ...padding];
});
const filteredFields = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  if (!query) return displayFields.value;
  return displayFields.value.filter((field) =>
    field.label.toLowerCase().includes(query),
  );
});
const pagedFields = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value;
  return filteredFields.value.slice(start, start + pageSize.value);
});

watch(keyword, () => {
  currentPage.value = 1;
});

watch(pageSize, () => {
  currentPage.value = 1;
});

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      keyword.value = "";
      currentPage.value = 1;
      pageSize.value = 5;
    }
  },
);
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    :title="`完整字段（${dataLabel} ${expectedCount}项）`"
    width="min(720px, 92vw)"
    top="12vh"
    class="full-fields-dialog"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="field-query">
      <label for="full-field-keyword">字段</label>
      <ElInput
        id="full-field-keyword"
        v-model="keyword"
        clearable
        placeholder="请输入字段名称"
      />
    </div>
    <h4>字段与数值</h4>
    <div class="field-table-panel">
      <ElTable :data="pagedFields" size="small">
        <ElTableColumn prop="label" label="字段" min-width="180" />
        <ElTableColumn label="数值" min-width="260">
          <template #default="scope">
            <span class="field-value" :title="scope.row.value || '—'">
              {{ scope.row.value || "—"
              }}{{ scope.row.unit ? ` ${scope.row.unit}` : "" }}
            </span>
          </template>
        </ElTableColumn>
      </ElTable>
    </div>
    <div class="field-pagination">
      <ElPagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[5, 10, 20, 50]"
        :total="filteredFields.length"
        layout="total, sizes, prev, pager, next"
        background
      />
    </div>
    <template #footer>
      <ElButton @click="$emit('update:modelValue', false)">关闭</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
:global(.full-fields-dialog .el-dialog__body) {
  padding-top: 10px;
  padding-bottom: 10px;
}

:global(.full-fields-dialog .el-dialog__footer) {
  padding-top: 8px;
}

h4 {
  margin: 10px 0 8px;
}

.field-query {
  display: flex;
  gap: var(--space-3);
  align-items: center;
}

.field-query label {
  flex: 0 0 auto;
  color: #1f2d3d;
  font-weight: 700;
}

.field-value {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-table-panel {
  border: 1px solid var(--color-neutral-200);
  border-radius: var(--radius-md);
}

.field-table-panel :deep(.el-table__cell) {
  padding: 5px 0;
}

.field-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

@media (width <= 640px) {
  .field-query {
    align-items: stretch;
    flex-direction: column;
    gap: var(--space-2);
  }

  .field-pagination {
    justify-content: flex-start;
  }
}
</style>
