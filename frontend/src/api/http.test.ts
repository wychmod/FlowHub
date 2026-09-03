import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, requestJson } from './http';

/**
 * 模拟一个 Response：requestJson 只用到 ok/status/json 三个成员，无需真实全局 fetch。
 */
function stubFetch(body: unknown, status = 200, jsonFails = false): ReturnType<typeof vi.fn> {
  const fake = {
    ok: status >= 200 && status < 300,
    status,
    json: async () => {
      if (jsonFails) throw new SyntaxError('unexpected token');
      return body;
    },
  } as unknown as Response;
  const mock = vi.fn().mockResolvedValue(fake);
  vi.stubGlobal('fetch', mock);
  return mock;
}

/** 构造一个成功 Envelope。 */
function successEnvelope(data: unknown) {
  return { code: 'SUCCESS', message: null, data, trace_id: 't-ok' };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('requestJson 成功路径', () => {
  it('只返回 Envelope 的 data，不含 code/message/trace_id 外壳', async () => {
    stubFetch(successEnvelope({ items: [1, 2], total: 2 }));
    await expect(requestJson<{ items: number[]; total: number }>('/api/v1/orders')).resolves.toEqual({
      items: [1, 2],
      total: 2,
    });
  });

  it('默认携带 Accept 头，有 body 时追加 Content-Type，自定义头原样合并', async () => {
    const mock = stubFetch(successEnvelope(null));
    await requestJson('/api/v1/export-jobs', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'key-1' },
      body: JSON.stringify({}),
    });

    const [, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(init.headers).toMatchObject({
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Idempotency-Key': 'key-1',
    });
  });
});

describe('requestJson 失败路径', () => {
  it('非 2xx 且为错误 Envelope 时抛出 code/message/status/trace_id 完整的 ApiError', async () => {
    stubFetch(
      { code: 'VALIDATION_ERROR', message: '参数错误', data: null, trace_id: 't-400' },
      400,
    );
    const error = await requestJson('/api/v1/orders').catch((e) => e as ApiError);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      code: 'VALIDATION_ERROR',
      message: '参数错误',
      status: 400,
      traceId: 't-400',
    });
  });

  it('错误 data 为字符串映射时提取为 fieldErrors（字段级校验契约占位）', async () => {
    stubFetch(
      {
        code: 'VALIDATION_ERROR',
        message: '参数错误',
        data: { page: '必须 ≥ 1' },
        trace_id: 't-401',
      },
      400,
    );
    await expect(requestJson('/api/v1/orders')).rejects.toMatchObject({
      fieldErrors: { page: '必须 ≥ 1' },
    });
  });

  it('错误 data 非「字符串键值映射」时不产出 fieldErrors', async () => {
    stubFetch(
      { code: 'INTERNAL_ERROR', message: '服务异常', data: { rows: [1] }, trace_id: 't-500' },
      500,
    );
    await expect(requestJson('/api/v1/orders')).rejects.toMatchObject({
      fieldErrors: undefined,
      status: 500,
    });
  });

  it('非 2xx 且非 JSON（网关 HTML 错误页）时抛带 HTTP 状态的兜底文案', async () => {
    stubFetch(null, 502, true);
    await expect(requestJson('/api/v1/orders')).rejects.toMatchObject({
      message: '请求失败（HTTP 502）',
      status: 502,
    });
  });

  it('2xx 但响应不是 JSON 时抛 Invalid API envelope', async () => {
    stubFetch(null, 200, true);
    await expect(requestJson('/api/v1/orders')).rejects.toMatchObject({
      message: 'Invalid API envelope',
      status: 200,
    });
  });

  it('2xx 但 JSON 缺少 code 字段（非合法 Envelope）时同样抛 Invalid API envelope', async () => {
    stubFetch({ foo: 'bar' }, 200);
    await expect(requestJson('/api/v1/orders')).rejects.toMatchObject({
      message: 'Invalid API envelope',
    });
  });
});
