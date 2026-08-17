import { z } from "zod";

import {
  CitySchema,
  CurrentSessionSchema,
  DashboardSummarySchema,
  UserPageSchema,
  type CreateSessionRequest,
  type CurrentSession,
  type DashboardSummary,
  type UserPage,
  type UserPageQuery,
} from "./contracts";
import type { HttpClient } from "./http-client";

export interface AppApi {
  sessions: {
    create(request: CreateSessionRequest): Promise<CurrentSession>;
    current(): Promise<CurrentSession>;
    remove(): Promise<void>;
  };
  dashboard: {
    getSummary(): Promise<DashboardSummary>;
  };
  users: {
    getPage(query: UserPageQuery): Promise<UserPage>;
  };
  cities: {
    getAll(): Promise<z.infer<typeof CitySchema>[]>;
  };
}

export function createHttpAppApi(client: HttpClient): AppApi {
  return {
    sessions: {
      create: async (request) =>
        CurrentSessionSchema.parse(
          await client.post("/api/v1/sessions", request),
        ),
      current: async () =>
        CurrentSessionSchema.parse(
          await client.get("/api/v1/sessions/current"),
        ),
      remove: async () => {
        await client.delete("/api/v1/sessions/current");
      },
    },
    dashboard: {
      getSummary: async () =>
        DashboardSummarySchema.parse(
          await client.get("/api/v1/dashboard/summary"),
        ),
    },
    users: {
      getPage: async ({ page, size }) => {
        const query = new URLSearchParams({
          page: String(page),
          size: String(size),
        });
        return UserPageSchema.parse(
          await client.get(`/api/v1/users?${query.toString()}`),
        );
      },
    },
    cities: {
      getAll: async () =>
        z.array(CitySchema).parse(await client.get("/api/v1/cities")),
    },
  };
}
