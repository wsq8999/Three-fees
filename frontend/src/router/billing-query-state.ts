import type { LocationQuery, LocationQueryRaw } from "vue-router";

import type { AuditStatus, BillingPointQuery } from "@/types/business";

const STATUSES: readonly AuditStatus[] = [
  "NORMAL",
  "OVER_LIMIT",
  "PENDING_REVIEW",
  "NOT_APPLICABLE",
];
const PAGE_SIZES = [10, 20, 50, 100] as const;

function first(
  value: LocationQuery[string] | LocationQueryRaw[string] | undefined,
): string {
  if (Array.isArray(value)) return String(value[0] ?? "");
  return String(value ?? "");
}

function positiveInteger(value: string, fallback: number): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

export function parseBillingPointQuery(
  query: LocationQuery | LocationQueryRaw | Record<string, string>,
): BillingPointQuery {
  const period = first(query.period);
  const status = first(query.status);
  const requestedSize = positiveInteger(first(query.size), 20);
  return {
    cityCode: first(query.city),
    period: /^\d{4}-(?:0[1-9]|1[0-2])$/.test(period) ? period : "",
    keyword: first(query.keyword),
    auditStatus: STATUSES.includes(status as AuditStatus)
      ? (status as AuditStatus)
      : "",
    page: positiveInteger(first(query.page), 1),
    size: PAGE_SIZES.includes(requestedSize as (typeof PAGE_SIZES)[number])
      ? requestedSize
      : 20,
  };
}

export function serializeBillingPointQuery(
  value: BillingPointQuery,
): LocationQueryRaw {
  return {
    ...(value.cityCode.length > 0 ? { city: value.cityCode } : {}),
    ...(value.period.length > 0 ? { period: value.period } : {}),
    ...((value.keyword ?? "").length > 0 ? { keyword: value.keyword } : {}),
    ...(value.auditStatus.length > 0 ? { status: value.auditStatus } : {}),
    page: String(value.page),
    size: String(value.size),
  };
}
