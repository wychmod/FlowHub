import { afterEach, describe, expect, it, vi } from 'vitest';
import { createExportJob, EXPORT_COLUMN_OPTIONS } from './exportApi';

/**
 * 模拟一个 Response：requestJson 只用到 ok/status/json 三个成员，无需真实全局 fetch。
 */
function stubFetch(body: unknown, status = 200): ReturnType<typeof vi.fn> {
  const fake = {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
  const mock = vi.fn().mockResolvedValue(fake);
  vi.stubGlobal('fetch', mock);
  return mock;
}

/** 构造一个符合 be-td.md 4.5 成功响应（HTTP 202 风格）的 Envelope。 */
function successEnvelope(data: unknown) {
  return { code: 'SUCCESS', message: null, data, trace_id: 't-1' };
}

const createdJob = {
  job_id: 1,
  job_no: 'EXP20260903-6F4A2C8D',
  status: 'PENDING',
  total_rows: 3,
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('createExportJob', () => {
  it('POST /api/v1/export-jobs，携带 Idempotency-Key 与 Content-Type，并解包 Envelope 的 data', async () => {
    const mock = stubFetch(successEnvelope(createdJob), 202);
    const result = await createExportJob(
      { selection: { mode: 'SELECTED_IDS', order_ids: [101, 203] }, columns: ['order_no', 'total_amount'] },
      'key-1',
    );

    expect(mock).toHaveBeenCalledTimes(1);
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/export-jobs');
    expect(init.method).toBe('POST');
    const headers = init.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toBe('key-1');
    expect(headers['Content-Type']).toBe('application/json');
    // 返回的是解包后的 data，不含 code/message/trace_id 外壳
    expect(result).toEqual(createdJob);
  });

  it('勾选模式请求体：order_ids 数组直传，file_name 可选携带', async () => {
    const mock = stubFetch(successEnvelope(createdJob), 202);
    await createExportJob(
      {
        selection: { mode: 'SELECTED_IDS', order_ids: [1, 2, 3] },
        columns: ['order_no'],
        file_name: 'paid-orders',
      },
      'key-2',
    );

    const [, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({
      selection: { mode: 'SELECTED_IDS', order_ids: [1, 2, 3] },
      columns: ['order_no'],
      file_name: 'paid-orders',
    });
  });

  it('筛选模式请求体：快照字段 snake_case、多值为数组、未设置字段不出现', async () => {
    const mock = stubFetch(successEnvelope(createdJob), 202);
    await createExportJob(
      {
        selection: {
          mode: 'FILTER',
          filter: {
            order_status: ['PAID', 'SHIPPED'],
            created_at_begin: '2026-01-01T00:00:00',
            created_at_end: '2026-02-01T00:00:00',
            sort_by: 'created_at',
            sort_order: 'desc',
          },
        },
        columns: ['order_no', 'total_amount', 'currency', 'created_at'],
      },
      'key-3',
    );

    const [, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({
      selection: {
        mode: 'FILTER',
        filter: {
          order_status: ['PAID', 'SHIPPED'],
          created_at_begin: '2026-01-01T00:00:00',
          created_at_end: '2026-02-01T00:00:00',
          sort_by: 'created_at',
          sort_order: 'desc',
        },
      },
      columns: ['order_no', 'total_amount', 'currency', 'created_at'],
    });
  });

  it('HTTP 非 2xx 时抛出带 status 与 trace_id 的 ApiError', async () => {
    stubFetch(
      { code: 'EXPORT_COLUMNS_INVALID', message: '导出列不存在', data: null, trace_id: 't-400' },
      400,
    );
    await expect(
      createExportJob({ selection: { mode: 'SELECTED_IDS', order_ids: [1] }, columns: [] }, 'key-4'),
    ).rejects.toMatchObject({
      code: 'EXPORT_COLUMNS_INVALID',
      traceId: 't-400',
      status: 400,
    });
  });
});

describe('EXPORT_COLUMN_OPTIONS', () => {
  it('共 9 列，默认勾选 6 列且顺序与 PRD 7.3.2 白名单一致', () => {
    expect(EXPORT_COLUMN_OPTIONS).toHaveLength(9);
    expect(EXPORT_COLUMN_OPTIONS.filter((option) => option.defaultSelected).map((option) => option.key)).toEqual([
      'order_no',
      'order_status',
      'sales_channel',
      'total_amount',
      'currency',
      'created_at',
    ]);
  });
});
