import { describe, expect, it } from "vitest";

import { canAccessRoute } from "@/router/access";

describe("route access", () => {
  it("allows authenticated users when no role is required", () => {
    expect(canAccessRoute(["CITY_USER"], undefined)).toBe(true);
  });

  it("allows a super administrator to open user management", () => {
    expect(canAccessRoute(["SUPER_ADMIN"], ["SUPER_ADMIN"])).toBe(true);
  });

  it("rejects a city user from user management", () => {
    expect(canAccessRoute(["CITY_USER"], ["SUPER_ADMIN"])).toBe(false);
  });
});
