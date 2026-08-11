import { PieChart } from "echarts/charts";
import { LegendComponent, TooltipComponent } from "echarts/components";
import { init, use, type EChartsType } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";

use([PieChart, LegendComponent, TooltipComponent, CanvasRenderer]);

export function createAuditChart(element: HTMLElement): EChartsType {
  return init(element);
}
