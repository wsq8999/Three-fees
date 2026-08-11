<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import type { EChartsType } from "echarts/core";

const props = defineProps<{
  billingPointCount: number;
  overLimitBillingPointCount: number;
}>();

const chartElement = ref<HTMLElement | null>(null);
const compliantCount = computed(() =>
  Math.max(0, props.billingPointCount - props.overLimitBillingPointCount),
);
const hasData = computed(() => props.billingPointCount > 0);
let chart: EChartsType | undefined;
let resizeObserver: ResizeObserver | undefined;

async function renderChart(): Promise<void> {
  if (!hasData.value) {
    chart?.dispose();
    chart = undefined;
    return;
  }
  await nextTick();
  if (chartElement.value === null) return;

  if (chart === undefined) {
    const { createAuditChart } = await import("./audit-chart-runtime");
    chart = createAuditChart(chartElement.value);
    resizeObserver = new ResizeObserver(() => chart?.resize());
    resizeObserver.observe(chartElement.value);
  }

  chart.setOption({
    color: ["#0a8f68", "#c63c3c"],
    tooltip: { trigger: "item" },
    legend: { bottom: 0, icon: "circle" },
    series: [
      {
        name: "报账点状态",
        type: "pie",
        radius: ["48%", "70%"],
        center: ["50%", "44%"],
        label: { formatter: "{b}\n{c}" },
        data: [
          { name: "未超标", value: compliantCount.value },
          { name: "超标", value: props.overLimitBillingPointCount },
        ],
      },
    ],
  });
}

watch(
  () => [props.billingPointCount, props.overLimitBillingPointCount],
  () => void renderChart(),
);

onMounted(() => void renderChart());
onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  chart?.dispose();
});
</script>

<template>
  <div
    v-if="hasData"
    ref="chartElement"
    class="audit-chart"
    role="img"
    :aria-label="`报账点审计分布：共 ${billingPointCount} 个，超标 ${overLimitBillingPointCount} 个`"
  />
  <ElEmpty v-else description="当前期间暂无报账点审计数据" :image-size="88" />
</template>

<style scoped>
.audit-chart {
  width: 100%;
  height: 280px;
}
</style>
