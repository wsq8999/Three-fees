<script setup lang="ts">
import { useRouter } from "vue-router";

import PageHeader from "@/components/PageHeader.vue";

const router = useRouter();
</script>

<template>
  <PageHeader
    title="访问受限"
    description="当前账号不具备此功能所需的角色权限。"
  />
  <section class="forbidden-card">
    <ElResult
      icon="warning"
      title="无权访问"
      sub-title="如需处理此业务，请联系超级管理员核对账号的数据范围。"
    >
      <template #extra>
        <ElButton type="primary" @click="router.replace('/dashboard')"
          >返回工作台</ElButton
        >
      </template>
    </ElResult>
  </section>
</template>

<style scoped>
.forbidden-card {
  min-height: 360px;
  padding: var(--space-8);
  background: var(--color-neutral-0);
  border: 1px solid var(--color-neutral-200);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
</style>
