<script setup lang="ts">
import type { OverLimitRatio } from "@/types/business";

defineProps<{
  ratios?: OverLimitRatio[] | null;
  emptyText?: string;
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
      :class="ratioClass(item.type)"
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
  border: 1px solid currentColor;
  background: color-mix(in srgb, currentColor 10%, #ffffff);
  font-size: 12px;
  line-height: 1.3;
}

.ratio-tag strong {
  font-weight: 700;
}

.ratio-label {
  opacity: 0.88;
}

.ratio-yoy {
  color: #d93044;
}

.ratio-mom {
  color: #c76f00;
}

.ratio-rated {
  color: #7c3aed;
}

.ratio-default {
  color: #64748b;
}

.ratio-empty {
  color: #8a96a8;
}
</style>
