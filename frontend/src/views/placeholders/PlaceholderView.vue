<script setup lang="ts">
import PageHeader from "@/components/PageHeader.vue";

defineProps<{
  featureName: string;
  iterationHint: string;
}>();
</script>

<template>
  <PageHeader
    :title="featureName"
    description="导航与页面边界已建立，业务能力将按迭代计划接入。"
  />
  <section class="placeholder-card">
    <ElEmpty :description="iterationHint" :image-size="128">
      <ElTag type="info" effect="plain">首阶段导航占位</ElTag>
    </ElEmpty>
  </section>
</template>

<style scoped>
.placeholder-card {
  min-height: 420px;
  padding: var(--space-10);
  background: var(--color-neutral-0);
  border: 1px dashed var(--color-neutral-300);
  border-radius: var(--radius-lg);
}
</style>
