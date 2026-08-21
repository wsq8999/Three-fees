<script setup lang="ts">
import type { OverLimitRatio } from "@/types/business";

const props = defineProps<{
  ratios?: OverLimitRatio[] | null;
  fallback?: string | null;
  emptyText?: string;
}>();

type TypeTag = {
  type: string;
  label: string;
};

const typeLabels: Record<string, string> = {
  YOY: "同比",
  MOM: "环比",
  RATED: "额定标杆",
};

function tagClass(type: string): string {
  const normalized = type.toUpperCase();
  if (normalized === "YOY") return "type-yoy";
  if (normalized === "MOM") return "type-mom";
  if (normalized === "RATED") return "type-rated";
  return "type-default";
}

function fallbackTags(value: string | null | undefined): TypeTag[] {
  if (!value || value === "—") return [];
  if (value.includes("同比") || value.includes("环比") || value.includes("额定")) {
    const tags: TypeTag[] = [];
    if (value.includes("同比")) tags.push({ type: "YOY", label: "同比" });
    if (value.includes("环比")) tags.push({ type: "MOM", label: "环比" });
    if (value.includes("额定")) tags.push({ type: "RATED", label: "额定标杆" });
    if (tags.length > 0) return tags;
  }
  return [{ type: "DEFAULT", label: value }];
}

function tags(): TypeTag[] {
  if (props.ratios && props.ratios.length > 0) {
    return props.ratios.map((item) => ({
      type: item.type,
      label: typeLabels[item.type.toUpperCase()] ?? item.label,
    }));
  }
  return fallbackTags(props.fallback);
}
</script>

<template>
  <div v-if="tags().length > 0" class="over-limit-type-tags">
    <span
      v-for="item in tags()"
      :key="`${item.type}-${item.label}`"
      class="type-tag"
      :class="tagClass(item.type)"
    >
      {{ item.label }}
    </span>
  </div>
  <span v-else class="type-empty">{{ emptyText ?? "—" }}</span>
</template>

<style scoped>
.over-limit-type-tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: inherit;
  min-width: 0;
}

.type-tag {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
  border-radius: 999px;
  padding: 3px 8px;
  border: 1px solid currentColor;
  background: color-mix(in srgb, currentColor 10%, #ffffff);
  font-size: 12px;
  line-height: 1.3;
  font-weight: 600;
}

.type-yoy {
  color: #d93044;
}

.type-mom {
  color: #c76f00;
}

.type-rated {
  color: #7c3aed;
}

.type-default {
  color: #64748b;
}

.type-empty {
  color: #8a96a8;
}
</style>
