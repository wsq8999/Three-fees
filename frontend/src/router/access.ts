import type { UserRole } from "@/api/contracts";

export function canAccessRoute(
  userRoles: readonly UserRole[],
  requiredRoles: readonly UserRole[] | undefined,
): boolean {
  return (
    requiredRoles === undefined ||
    requiredRoles.some((role) => userRoles.includes(role))
  );
}
