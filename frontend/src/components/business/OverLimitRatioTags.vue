<script setup lang="ts">
import type { OverLimitRatio } from "@/types/business";

defineProps<{
  ratios?: OverLimitRatio[] | null;
  emptyText?: string;
  quiet?: boolean;
}>();

function ratioClass(type: string): string {
  const normalized = type.toUpperCase();
  if (normalized === "YOY") return "ratio-yoy";
  if (normalized === "MOM") return "ratio-mom";
  if (normalized === "RATED") return "ratio-rated";
  return "ratio-default";
}

function formatRatio(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const text = String(value);
  const raw = text.endsWith("%") ? text.slice(0, -1) : text;
  const number = Number(raw);
  if (!Number.isFinite(number)) return text;
  return `${number.toFixed(2)}%`;
}
</script>

<template>
  <div v-if="ratios && ratios.length > 0" class="over-limit-ratio-tags">
    <span
      v-for="item in ratios"
      :key="`${item.type}-${item.label}`"
      class="ratio-tag"
      :class="[ratioClass(item.type), { 'is-quiet': quiet }]"
    >
      <span class="ratio-label">{{ item.label }}</span>
      <strong>{{ formatRatio(item.ratio) }}</strong>
    </span>
  </div>
  <span v-else class="ratio-empty">{{ emptyText ?? "—" }}</span>
</template>

<style scoped>
.over-limit-ratio-tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: inherit;
  min-width: 0;
}

.ratio-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  border-radius: 999px;
  padding: 3px 8px;
  border: 1px solid var(--ratio-tag-border, #cbd5e1);
  color: var(--ratio-tag-color, #64748b);
  background: var(--ratio-tag-bg, #f8fafc);
  font-size: 12px;
  line-height: 1.3;
}

.ratio-tag.is-quiet {
  padding: 2px 7px;
}

.ratio-tag strong {
  font-weight: 700;
}

.ratio-tag.is-quiet strong {
  font-weight: 600;
}

.ratio-label {
  opacity: 0.88;
}

.ratio-yoy {
  --ratio-tag-color: #c14453;
  --ratio-tag-bg: #fff1f3;
  --ratio-tag-border: #f6b6be;
}

.ratio-mom {
  --ratio-tag-color: #c27a2c;
  --ratio-tag-bg: #fff7ed;
  --ratio-tag-border: #f4c38e;
}

.ratio-rated {
  --ratio-tag-color: #3b73c4;
  --ratio-tag-bg: #eff8ff;
  --ratio-tag-border: #a9c7f2;
}

.ratio-default {
  --ratio-tag-color: #64748b;
  --ratio-tag-bg: #f8fafc;
  --ratio-tag-border: #cbd5e1;
}

.ratio-empty {
  color: #8a96a8;
}
</style>
