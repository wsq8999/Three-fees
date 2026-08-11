<script setup lang="ts">
defineProps<{
  title: string;
  description?: string;
}>();
</script>

<template>
  <header class="page-header">
    <div>
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  gap: var(--space-4);
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--space-6);
}

h1 {
  margin: 0;
  color: var(--color-neutral-900);
  font-size: var(--font-size-xl);
  line-height: var(--line-height-tight);
}

p {
  margin: var(--space-2) 0 0;
  color: var(--color-neutral-500);
  font-size: var(--font-size-sm);
}

.page-header-actions {
  display: flex;
  flex: none;
  gap: var(--space-2);
}

@media (width <= 640px) {
  .page-header {
    flex-direction: column;
  }
}
</style>
