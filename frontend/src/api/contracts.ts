import { z } from "zod";

export const UserRoleSchema = z.enum(["SUPER_ADMIN", "CITY_USER"]);
export type UserRole = z.infer<typeof UserRoleSchema>;

export const CitySchema = z.object({
  code: z.string().min(1),
  name: z.string().min(1),
});
export type City = z.infer<typeof CitySchema>;

export const UserSchema = z.object({
  id: z.string().min(1),
  username: z.string().min(1),
  displayName: z.string().min(1),
  roles: z.array(UserRoleSchema).min(1),
  city: CitySchema.nullable(),
  mustChangePassword: z.boolean(),
});
export type User = z.infer<typeof UserSchema>;

export const CurrentSessionSchema = z.object({
  user: UserSchema,
});
export type CurrentSession = z.infer<typeof CurrentSessionSchema>;

export interface CreateSessionRequest {
  username: string;
  password: string;
}

export const DashboardSummarySchema = z.object({
  currentDataPeriod: z
    .string()
    .regex(/^\d{4}-(?:0[1-9]|1[0-2])$/)
    .nullable(),
  cityCount: z.number().int().nonnegative(),
  billingPointCount: z.number().int().nonnegative(),
  overLimitBillingPointCount: z.number().int().nonnegative(),
  draftReportCount: z.number().int().nonnegative(),
});
export type DashboardSummary = z.infer<typeof DashboardSummarySchema>;

export const UserPageSchema = z.object({
  items: z.array(UserSchema),
  page: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  totalElements: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
});
export type UserPage = z.infer<typeof UserPageSchema>;

export interface UserPageQuery {
  page: number;
  size: number;
}
