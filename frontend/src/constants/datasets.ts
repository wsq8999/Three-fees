import type { DatasetType } from "@/types/business";

export const DATASET_TYPES: DatasetType[] = [
  "BILLING_POINT",
  "PAYMENT",
  "METER_READING",
  "BENCHMARK",
];

export const DATASET_META: Record<
  DatasetType,
  { label: string; fieldCount: number; acceptedFormats: string }
> = {
  BILLING_POINT: {
    label: "报账点清单",
    fieldCount: 73,
    acceptedFormats: ".xlsx / .xls / .csv",
  },
  PAYMENT: {
    label: "缴费明细",
    fieldCount: 198,
    acceptedFormats: ".xlsx / .xls / .csv",
  },
  METER_READING: {
    label: "电表读数",
    fieldCount: 42,
    acceptedFormats: ".xlsx / .xls / .csv",
  },
  BENCHMARK: {
    label: "标杆数据",
    fieldCount: 39,
    acceptedFormats: ".xlsx / .xls / .csv",
  },
};
