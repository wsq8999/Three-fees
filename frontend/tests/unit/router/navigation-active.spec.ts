import { resolveNavigationActivePath } from "@/router/navigation-active";

describe("report navigation highlighting", () => {
  it("keeps generate and history branches distinct", () => {
    expect(resolveNavigationActivePath("/reports/generate")).toBe(
      "/reports/generate",
    );
    expect(resolveNavigationActivePath("/reports/drafts/draft-1")).toBe(
      "/reports/generate",
    );
    expect(resolveNavigationActivePath("/reports/history")).toBe(
      "/reports/history",
    );
    expect(resolveNavigationActivePath("/reports/report-1")).toBe(
      "/reports/history",
    );
    expect(resolveNavigationActivePath("/reports/report-1/correction")).toBe(
      "/reports/history",
    );
  });
});
