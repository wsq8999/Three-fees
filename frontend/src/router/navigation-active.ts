export function resolveNavigationActivePath(path: string): string {
  if (path === "/billing-points" || path.startsWith("/billing-points/")) {
    return "/billing-points";
  }
  if (path === "/reports/generate") {
    return "/reports/generate";
  }
  if (
    path === "/reports/history" ||
    path.startsWith("/reports/drafts/") ||
    /^\/reports\/[^/]+(?:\/correction)?$/.test(path)
  ) {
    return "/reports/history";
  }
  if (path.startsWith("/reports/")) return "/reports/history";
  return path;
}
