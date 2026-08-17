export function resolveNavigationActivePath(path: string): string {
  if (path === "/billing-points" || path.startsWith("/billing-points/")) {
    return "/billing-points";
  }
  if (path === "/reports/generate" || path.startsWith("/reports/drafts/")) {
    return "/reports/generate";
  }
  if (
    path === "/reports/history" ||
    /^\/reports\/[^/]+(?:\/correction)?$/.test(path)
  ) {
    return "/reports/history";
  }
  if (path.startsWith("/reports/")) return "/reports/history";
  return path;
}
