/**
 * HTTP 基础封装（fe-td.md 4）：JSON 请求防腐层。
 * 页面只消费业务数据与 ApiError，请求头、Envelope 解包、错误转换与 trace_id 保留全部收敛在本层。
 */

/** 统一响应 Envelope，见 be-td.md 4.2。 */
export interface ApiEnvelope<T> {
  code: string;
  message: string | null;
  data: T;
  trace_id: string;
}

/** 业务/HTTP 错误：页面统一 catch，无需再解析原始响应。 */
export class ApiError extends Error {
  readonly code?: string;
  readonly traceId?: string;
  readonly status: number;
  /** 字段级校验错误（VALIDATION_ERROR 时 Envelope data 携带 field → 文案映射，契约占位见 fe-td.md 8）。 */
  readonly fieldErrors?: Readonly<Record<string, string>>;

  constructor(
    message: string,
    code?: string,
    traceId?: string,
    status = 0,
    fieldErrors?: Readonly<Record<string, string>>,
  ) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

/**
 * 拼接 API 基础地址。默认留空走 Vite 开发代理（vite.config.ts 中 /api -> 8080）；
 * 如需直连后端，可在 .env.local 中配置 VITE_API_BASE_URL=http://localhost:8080。
 */
export function apiBaseUrl(path: string): string {
  const base = (import.meta.env.VITE_API_BASE_URL ?? '').trim();
  return `${base}${path}`;
}

/** 解析响应体 JSON；非 JSON 响应（如网关 HTML 错误页）返回 null。 */
async function parseJsonBody(response: Response): Promise<unknown | null> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

/** Envelope 结构校验：普通对象且 code 为字符串才合法，其余形态（含数组）返回 null。 */
export function asEnvelope(value: unknown): ApiEnvelope<unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return typeof (value as Record<string, unknown>).code === 'string'
    ? (value as ApiEnvelope<unknown>)
    : null;
}

/** 提取字段级校验错误：仅当 data 是「字符串键 → 字符串值」的非空普通对象时成立。 */
function fieldErrorsFrom(
  envelope: ApiEnvelope<unknown> | null,
): Readonly<Record<string, string>> | undefined {
  const { data } = envelope ?? {};
  if (!data || typeof data !== 'object' || Array.isArray(data)) return undefined;
  const entries = Object.entries(data);
  if (entries.length === 0 || !entries.every(([, value]) => typeof value === 'string')) {
    return undefined;
  }
  return Object.fromEntries(entries) as Readonly<Record<string, string>>;
}

/** 把错误 Envelope / 非 Envelope 响应统一转换为 ApiError（保留 code/trace_id/status/fieldErrors）。 */
export function envelopeToError(
  envelope: ApiEnvelope<unknown> | null,
  status: number,
  fallbackMessage: string,
): ApiError {
  if (!envelope) {
    return new ApiError(fallbackMessage, undefined, undefined, status);
  }
  return new ApiError(
    envelope.message ?? fallbackMessage,
    envelope.code,
    envelope.trace_id,
    status,
    fieldErrorsFrom(envelope),
  );
}

/** 所有 JSON REST 接口的统一入口：成功只返回 Envelope 的 data，失败抛 ApiError。 */
export async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(apiBaseUrl(path), {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  });

  const body = await parseJsonBody(response);

  if (!response.ok) {
    throw envelopeToError(asEnvelope(body), response.status, `请求失败（HTTP ${response.status}）`);
  }
  // 2xx 但非合法 Envelope（非 JSON 已折叠为 null）：页面不得拿到残缺数据
  const envelope = asEnvelope(body);
  if (!envelope) {
    throw new ApiError('Invalid API envelope', undefined, undefined, response.status);
  }
  return envelope.data as T;
}
