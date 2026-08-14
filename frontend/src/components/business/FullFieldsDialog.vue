<script setup lang="ts">
import { computed } from "vue";

import type { BusinessField, FieldGroup } from "@/types/business";

const props = defineProps<{
  modelValue: boolean;
  dataLabel: string;
  expectedCount: number;
  summary: BusinessField[];
  groups: FieldGroup[];
}>();

defineEmits<{ "update:modelValue": [value: boolean] }>();

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
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    :title="`完整字段（${dataLabel} ${expectedCount}项）`"
    width="min(920px, 92vw)"
    top="7vh"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <p class="dialog-description">
      仅展示当前报账点、当前账期和当前所选记录的源字段值
    </p>
    <h4>搜索字段</h4>
    <div class="summary-fields">
      <div v-for="field in summary" :key="field.key">
        <small>{{ field.label }}</small
        ><strong>{{ field.value || "—" }}</strong>
      </div>
    </div>
    <h4>字段与数值</h4>
    <div class="field-table-scroll">
      <ElTable :data="displayFields" size="small" height="430">
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
    <template #footer>
      <ElButton @click="$emit('update:modelValue', false)">关闭</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.dialog-description {
  margin: -10px 0 var(--space-4);
  color: var(--color-neutral-500);
  font-size: var(--font-size-sm);
}

h4 {
  margin: var(--space-3) 0 var(--space-2);
}

.summary-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 160px), 1fr));
  gap: 0;
  padding: var(--space-3) 0;
  background: #f7f9fc;
  border: 1px solid var(--color-neutral-200);
  border-radius: var(--radius-lg);
}

.summary-fields > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--space-1);
  padding: 0 var(--space-4);
  border-right: 1px solid var(--color-neutral-200);
}

.summary-fields > div:last-child {
  border-right: 0;
}

.summary-fields small {
  color: var(--color-neutral-500);
}

.summary-fields strong,
.field-value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-table-scroll {
  border: 1px solid var(--color-neutral-200);
}

@media (width <= 640px) {
  .summary-fields {
    grid-template-columns: 1fr;
  }

  .summary-fields > div {
    padding-block: var(--space-2);
    border-right: 0;
    border-bottom: 1px solid var(--color-neutral-200);
  }
}
</style>
