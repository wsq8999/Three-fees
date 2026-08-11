<script setup lang="ts">
import type { FieldGroup } from "@/types/business";

defineProps<{
  groups: FieldGroup[];
  paired?: boolean;
}>();
</script>

<template>
  <div class="field-grid" :class="{ 'field-grid--paired': paired }">
    <section v-for="group in groups" :key="group.title" class="field-card">
      <h3>{{ group.title }}</h3>
      <dl>
        <div v-for="field in group.fields" :key="field.key">
          <dt>{{ field.label }}</dt>
          <dd :title="field.value || '—'">
            {{ field.value || "—" }}<small v-if="field.unit"> {{ field.unit }}</small>
          </dd>
        </div>
      </dl>
    </section>
  </div>
</template>

<style scoped>
.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.field-card {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 8px;
}

.field-card h3 {
  margin: 0 0 12px;
  color: #0f1f35;
  font-size: 14px;
  font-weight: 700;
}

.field-card dl,
.field-card dd {
  margin: 0;
}

.field-card dl {
  display: grid;
  grid-template-columns: 1fr;
  column-gap: 12px;
}

.field-grid--paired .field-card dl {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.field-card dl > div {
  display: grid;
  grid-template-columns: minmax(96px, 0.9fr) minmax(96px, 1.1fr);
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px dashed #e6ebf2;
}

.field-card dl > div:last-child {
  border-bottom: 0;
}

.field-grid--paired .field-card dl > div:nth-last-child(-n + 2) {
  border-bottom: 0;
}

.field-card dt {
  color: #7d8ca1;
  font-size: 12px;
}

.field-card dd {
  overflow: hidden;
  color: #0f1f35;
  font-size: 12px;
  font-weight: 700;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-card small {
  color: #7d8ca1;
  font-weight: 400;
}

@media (width <= 720px) {
  .field-grid {
    grid-template-columns: 1fr;
  }

  .field-card dl {
    grid-template-columns: 1fr;
  }

  .field-grid--paired .field-card dl > div:nth-last-child(2) {
    border-bottom: 1px dashed #e6ebf2;
  }
}
</style>
