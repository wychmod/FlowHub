/**
 * HTTP 基础封装（fe-td.md 4）：
 * 统一处理请求头、JSON 解析、HTTP 错误与业务错误 Envelope。
 */

/** 统一响应 Envelope，见 be-td.md 4.2。 */
export interface ApiEnvelope<T> {
  code: string;
  message: string | null;
  data: T;
  trace_id: string;
}

/** 业务/HTTP 错误，携带错误码、trace_id 与 HTTP 状态。 */
export class ApiError extends Error {
  readonly code?: string;
  readonly traceId?: string;
  readonly status: number;

  constructor(message: string, code?: string, traceId?: string, status = 0) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.traceId = traceId;
    this.status = status;
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

/** 解析响应体为 Envelope；响应不是 JSON（如网关 HTML 错误页）时返回 null。 */
async function parseJsonBody(response: Response): Promise<ApiEnvelope<unknown> | null> {
  try {
    return (await response.json()) as ApiEnvelope<unknown>;
  } catch {
    return null;
  }
}

/** 把错误 Envelope / 非 JSON 响应转换为 ApiError。 */
function envelopeToError(
  body: ApiEnvelope<unknown> | null,
  status: number,
  fallbackMessage: string,
): ApiError {
  if (body) {
    return new ApiError(body.message ?? fallbackMessage, body.code, body.trace_id, status);
  }
  return new ApiError(fallbackMessage, undefined, undefined, status);
}

/** 所有 JSON REST 接口的统一入口，成功时直接返回 Envelope 中的 data。 */
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
    throw envelopeToError(body, response.status, `请求失败（HTTP ${response.status}）`);
  }
  if (!body) {
    throw new ApiError('响应不是合法的 JSON', undefined, undefined, response.status);
  }
  return body.data as T;
}
