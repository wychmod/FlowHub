/**
 * Actuator 健康检查防腐层：/actuator/health 返回 Spring Boot 原生 JSON（非统一 Envelope），
 * 不能复用 requestJson 的 Envelope 解包，独立做结构校验与错误转换。
 */
import { apiBaseUrl, ApiError } from './http';

/** 组件健康状态（Spring Boot 标准取值，宽松保留 string 以防版本新增）。 */
export type HealthStatus = 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN' | (string & {});

/** /actuator/health 响应：show-details 开启时携带 components 细分。 */
export interface HealthReport {
  status: HealthStatus;
  components?: Record<string, { status: HealthStatus }>;
}

/** 查询后端健康报告；非 200 / 非 JSON / 缺 status 字段一律抛 ApiError（不可达语义）。 */
export async function fetchHealth(): Promise<HealthReport> {
  const response = await fetch(apiBaseUrl('/actuator/health'), {
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    throw new ApiError(`健康检查失败（HTTP ${response.status}）`, undefined, undefined, response.status);
  }
  const body = (await response.json().catch(() => null)) as HealthReport | null;
  if (!body || typeof body.status !== 'string') {
    throw new ApiError('Invalid health payload', undefined, undefined, response.status);
  }
  return body;
}
