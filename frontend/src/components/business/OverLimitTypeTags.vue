<script setup lang="ts">
import type { OverLimitRatio } from "@/types/business";

const props = defineProps<{
  ratios?: OverLimitRatio[] | null;
  fallback?: string | null;
  emptyText?: string;
  quiet?: boolean;
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
      :class="[tagClass(item.type), { 'is-quiet': quiet }]"
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
  border: 1px solid var(--type-tag-border, #cbd5e1);
  color: var(--type-tag-color, #64748b);
  background: var(--type-tag-bg, #f8fafc);
  font-size: 12px;
  line-height: 1.3;
  font-weight: 600;
}

.type-tag.is-quiet {
  padding: 2px 7px;
  font-weight: 500;
}

.type-yoy {
  --type-tag-color: #c14453;
  --type-tag-bg: #fff1f3;
  --type-tag-border: #f6b6be;
}

.type-mom {
  --type-tag-color: #c27a2c;
  --type-tag-bg: #fff7ed;
  --type-tag-border: #f4c38e;
}

.type-rated {
  --type-tag-color: #3b73c4;
  --type-tag-bg: #eff8ff;
  --type-tag-border: #a9c7f2;
}

.type-default {
  --type-tag-color: #64748b;
  --type-tag-bg: #f8fafc;
  --type-tag-border: #cbd5e1;
}

.type-empty {
  color: #8a96a8;
}
</style>
