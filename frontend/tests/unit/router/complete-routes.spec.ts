import { router } from "@/router";

describe("complete business routes", () => {
  it("exposes every prototype page as a named route", () => {
    const names = new Set(router.getRoutes().map((route) => route.name));

    for (const expected of [
      "billing-points",
      "billing-point-detail",
      "reports-generate",
      "report-draft",
      "reports-history",
      "report-detail",
      "report-correction",
      "benchmark-rules",
      "users",
    ]) {
      expect(names.has(expected)).toBe(true);
    }

    const imports = router.resolve("/imports");
    expect(imports.name).toBe("imports-compat");
    expect(
      router.getRoutes().some((route) => route.path === "/reports/generate"),
    ).toBe(true);
  });
});
