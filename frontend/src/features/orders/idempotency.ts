import type { CreateExportJobPayload } from '../../api/exportApi';

/**
 * 导出创建幂等键生命周期（PRD 7.4.2）：
 * 点击「确认创建」时生成 → 同一提交意图（同 payload）重试复用 → 创建成功/取消后失效，
 * 修改表单（payload 变化）后重新提交生成新 Key。
 * <p>
 * 页面层用 useRef 持有进行中的意图，本模块只提供纯函数判定，便于单测。
 */

/** 进行中的提交意图：幂等键 + payload 指纹。 */
export interface ExportAttempt {
  key: string;
  fingerprint: string;
}

/** payload 指纹：对象字面量构造顺序固定（列按白名单序输出），JSON 序列化即稳定。 */
export function exportAttemptFingerprint(payload: CreateExportJobPayload): string {
  return JSON.stringify(payload);
}

/** 解析本次提交的幂等键：payload 与进行中意图一致则复用（网络重试），否则生成新 Key。 */
export function resolveIdempotencyKey(
  attempt: ExportAttempt | null,
  payload: CreateExportJobPayload,
): ExportAttempt {
  const fingerprint = exportAttemptFingerprint(payload);
  if (attempt && attempt.fingerprint === fingerprint) {
    return attempt;
  }
  return { key: crypto.randomUUID(), fingerprint };
}