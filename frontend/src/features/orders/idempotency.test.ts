import { describe, expect, it } from 'vitest';
import { resolveIdempotencyKey } from './idempotency';
import type { CreateExportJobPayload } from '../../api/exportApi';

const payload: CreateExportJobPayload = {
  selection: { mode: 'SELECTED_IDS', order_ids: [1, 2] },
  columns: ['order_no', 'total_amount'],
};

describe('resolveIdempotencyKey', () => {
  it('新提交生成新幂等键并记录指纹', () => {
    const attempt = resolveIdempotencyKey(null, payload);
    expect(attempt.key).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
    expect(attempt.fingerprint).toBe(JSON.stringify(payload));
  });

  it('同 payload 重试复用同一 Key（网络重试）', () => {
    const first = resolveIdempotencyKey(null, payload);
    const retried = resolveIdempotencyKey(first, { ...payload });
    expect(retried.key).toBe(first.key);
  });

  it('payload 变化（修改表单）后生成新 Key', () => {
    const first = resolveIdempotencyKey(null, payload);
    const changed = resolveIdempotencyKey(first, { ...payload, file_name: 'paid-orders' });
    expect(changed.key).not.toBe(first.key);
  });

  it('意图终结（成功/取消）后重新提交生成新 Key', () => {
    const first = resolveIdempotencyKey(null, payload);
    const next = resolveIdempotencyKey(null, payload);
    expect(next.key).not.toBe(first.key);
  });
});