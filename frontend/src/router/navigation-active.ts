export function resolveNavigationActivePath(path: string): string {
  if (path === "/reports/generate" || path.startsWith("/reports/drafts/")) {
    return "/reports/generate";
  }
  if (path === "/reports/history") return "/reports/history";
  if (/^\/reports\/[^/]+(?:\/correction)?$/.test(path)) {
    return "/reports/history";
  }
  return path;
}
