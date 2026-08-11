import { z } from "zod";

const FieldErrorSchema = z.object({
  field: z.string(),
  code: z.string(),
  message: z.string(),
});

const ProblemDetailsSchema = z.object({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().int().optional(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  code: z.string().optional(),
  traceId: z.string().optional(),
  fieldErrors: z.array(FieldErrorSchema).optional(),
});

export type FieldError = z.infer<typeof FieldErrorSchema>;

export class ApiProblem extends Error {
  override readonly name = "ApiProblem";
  readonly type: string;
  readonly status: number;
  readonly code: string;
  readonly detail: string;
  readonly instance: string;
  readonly traceId: string | undefined;
  readonly fieldErrors: readonly FieldError[];

  constructor(input: {
    type?: string;
    title?: string;
    status: number;
    code?: string;
    detail?: string;
    instance?: string;
    traceId?: string;
    fieldErrors?: readonly FieldError[];
  }) {
    super(input.title ?? "请求未能完成");
    this.type = input.type ?? "about:blank";
    this.status = input.status;
    this.code = input.code ?? "REQUEST_FAILED";
    this.detail = input.detail ?? "服务暂时不可用，请稍后重试。";
    this.instance = input.instance ?? "";
    this.traceId = input.traceId;
    this.fieldErrors = input.fieldErrors ?? [];
  }
}

export function parseProblemDetails(
  value: unknown,
  fallback: { status: number; title?: string; instance?: string },
): ApiProblem {
  const parsed = ProblemDetailsSchema.safeParse(value);
  if (!parsed.success) {
    return new ApiProblem(fallback);
  }

  return new ApiProblem({
    status: parsed.data.status ?? fallback.status,
    ...(parsed.data.type === undefined ? {} : { type: parsed.data.type }),
    ...(parsed.data.title === undefined
      ? fallback.title === undefined
        ? {}
        : { title: fallback.title }
      : { title: parsed.data.title }),
    ...(parsed.data.code === undefined ? {} : { code: parsed.data.code }),
    ...(parsed.data.detail === undefined ? {} : { detail: parsed.data.detail }),
    ...(parsed.data.instance === undefined
      ? fallback.instance === undefined
        ? {}
        : { instance: fallback.instance }
      : { instance: parsed.data.instance }),
    ...(parsed.data.traceId === undefined
      ? {}
      : { traceId: parsed.data.traceId }),
    ...(parsed.data.fieldErrors === undefined
      ? {}
      : { fieldErrors: parsed.data.fieldErrors }),
  });
}

export function toApiProblem(error: unknown): ApiProblem {
  if (error instanceof ApiProblem) {
    return error;
  }
  if (error instanceof Error && error.name === "AbortError") {
    return new ApiProblem({
      status: 0,
      code: "REQUEST_TIMEOUT",
      title: "请求超时",
      detail: "服务响应超时，请稍后重试。",
    });
  }
  return new ApiProblem({
    status: 0,
    code: "NETWORK_ERROR",
    title: "网络连接失败",
    detail: "无法连接到服务，请检查网络后重试。",
  });
}
