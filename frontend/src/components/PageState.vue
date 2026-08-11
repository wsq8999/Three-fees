<script setup lang="ts">
defineProps<{
  kind: "loading" | "empty" | "error" | "forbidden";
  title?: string;
  description?: string;
}>();

defineEmits<{
  retry: [];
}>();
</script>

<template>
  <section class="page-state" :aria-busy="kind === 'loading'">
    <ElSkeleton v-if="kind === 'loading'" :rows="6" animated />
    <ElEmpty
      v-else-if="kind === 'empty'"
      :description="description ?? '暂无数据'"
      :image-size="112"
    />
    <ElResult
      v-else
      :icon="kind === 'forbidden' ? 'warning' : 'error'"
      :title="title ?? (kind === 'forbidden' ? '无权访问' : '加载失败')"
      :sub-title="description ?? ''"
    >
      <template v-if="kind === 'error'" #extra>
        <ElButton type="primary" @click="$emit('retry')">重新加载</ElButton>
      </template>
    </ElResult>
  </section>
</template>

<style scoped>
.page-state {
  min-height: 320px;
  padding: var(--space-8);
  background: var(--color-neutral-0);
  border: 1px solid var(--color-neutral-200);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
</style>
