import {
  parseBillingPointQuery,
  serializeBillingPointQuery,
} from "@/router/billing-query-state";

describe("billing point query state", () => {
  it("round-trips filters and pagination through the URL", () => {
    const value = {
      cityCode: "320100",
      period: "2026-06",
      keyword: "NJ-001",
      auditStatus: "OVER_LIMIT" as const,
      page: 3,
      size: 50,
    };

    expect(parseBillingPointQuery(serializeBillingPointQuery(value))).toEqual(
      value,
    );
  });

  it("normalizes invalid query values to safe defaults", () => {
    expect(
      parseBillingPointQuery({
        page: "-1",
        size: "9999",
        status: "UNKNOWN",
        period: "June",
      }),
    ).toEqual({
      cityCode: "",
      period: "",
      keyword: "",
      auditStatus: "",
      page: 1,
      size: 20,
    });
  });
});
