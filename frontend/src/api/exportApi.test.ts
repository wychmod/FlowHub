import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createExportJob,
  downloadExportJob,
  EXPORT_COLUMN_OPTIONS,
  exportEventsUrl,
  retryExportJob,
  type ExportJobItem,
} from './exportApi';

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

/** 构造一个 HTTP 202 风格成功响应的 Envelope。 */
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

describe('retryExportJob', () => {
  it('POST /api/v1/export-jobs/{id}/retry 并解包 Envelope 的 data', async () => {
    const retried = { ...createdJob, status: 'PENDING' };
    const mock = stubFetch(successEnvelope(retried), 202);
    const result = await retryExportJob(91);

    expect(mock).toHaveBeenCalledTimes(1);
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/export-jobs/91/retry');
    expect(init.method).toBe('POST');
    expect(result).toEqual(retried);
  });

  it('重试被拒（非 FAILED / 达上限）时抛出 409 ExportJobErrorCode', async () => {
    stubFetch(
      { code: 'EXPORT_JOB_NOT_RETRYABLE', message: '任务不可重试', data: null, trace_id: 't-409' },
      409,
    );
    await expect(retryExportJob(91)).rejects.toMatchObject({
      code: 'EXPORT_JOB_NOT_RETRYABLE',
      traceId: 't-409',
      status: 409,
    });
  });
});

describe('exportEventsUrl', () => {
  it('SSE 事件流端点为 /api/v1/export-jobs/events（EventSource 消费）', () => {
    expect(exportEventsUrl).toBe('/api/v1/export-jobs/events');
  });
});

describe('downloadExportJob', () => {
  /** 下载场景 Response 桩：成功是文件流、失败可能是 JSON 错误包或网关文本。 */
  function stubDownloadResponse(options: {
    ok: boolean;
    status: number;
    body?: string;
    disposition?: string;
  }): ReturnType<typeof vi.fn> {
    const fake = {
      ok: options.ok,
      status: options.status,
      blob: async () => ({ text: async () => options.body ?? '' }),
      headers: {
        get: (name: string) =>
          name.toLowerCase() === 'content-disposition' ? (options.disposition ?? null) : null,
      },
    } as unknown as Response;
    const mock = vi.fn().mockResolvedValue(fake);
    vi.stubGlobal('fetch', mock);
    return mock;
  }

  /** node 测试环境无 DOM：stub document/window 供 saveBlob 触发下载。 */
  function stubDownloadDom() {
    const anchors: { href?: string; download?: string; click: ReturnType<typeof vi.fn> }[] = [];
    vi.stubGlobal('document', {
      createElement: () => {
        const a = { click: vi.fn() };
        anchors.push(a);
        return a;
      },
      body: { appendChild: vi.fn(), removeChild: vi.fn() },
    });
    const url = { createObjectURL: vi.fn(() => 'blob:mock-url'), revokeObjectURL: vi.fn() };
    vi.stubGlobal('window', { URL: url });
    return { anchors, url };
  }

  const succeededJob: ExportJobItem = {
    job_id: 91,
    job_no: 'EXP20260903-6F4A2C8D',
    status: 'SUCCEEDED',
    job_version: 3,
    processed_rows: 100,
    total_rows: 100,
    progress_percent: 100,
    downloadable: true,
    file_size_bytes: 2048,
    error_code: null,
    error_message: null,
    created_at: '2026-09-03T10:00:00',
    finished_at: '2026-09-03T10:00:05',
    expired_at: '2026-09-04T10:00:00',
    file_name: 'orders_91.xlsx',
  };

  it('请求下载地址并携带 xlsx Accept 头，按 Content-Disposition 文件名触发保存', async () => {
    const mock = stubDownloadResponse({
      ok: true,
      status: 200,
      disposition: `attachment; filename*=UTF-8''%E8%AE%A2%E5%8D%95.xlsx`,
    });
    const dom = stubDownloadDom();

    await downloadExportJob(succeededJob);

    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/export-jobs/91/download');
    expect(init.headers).toMatchObject({
      Accept: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    expect(dom.anchors[0].download).toBe('订单.xlsx');
    expect(dom.url.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url');
  });

  it('无 Content-Disposition 时回退任务 file_name', async () => {
    stubDownloadResponse({ ok: true, status: 200 });
    const dom = stubDownloadDom();

    await downloadExportJob(succeededJob);

    expect(dom.anchors[0].download).toBe('orders_91.xlsx');
  });

  it('无响应头且任务缺 file_name 时用任务编号兜底命名', async () => {
    stubDownloadResponse({ ok: true, status: 200 });
    const dom = stubDownloadDom();

    await downloadExportJob({ ...succeededJob, file_name: undefined });

    expect(dom.anchors[0].download).toBe('export-EXP20260903-6F4A2C8D.xlsx');
  });

  it('file_name 缺 .xlsx 后缀时兜底命名补齐后缀（对齐后端展示名规则）', async () => {
    stubDownloadResponse({ ok: true, status: 200 });
    const dom = stubDownloadDom();

    await downloadExportJob({ ...succeededJob, file_name: 'orders_91' });

    expect(dom.anchors[0].download).toBe('orders_91.xlsx');
  });

  it('410 JSON 错误包（伪装成下载响应）抛出带 code/trace_id 的 ApiError', async () => {
    stubDownloadResponse({
      ok: false,
      status: 410,
      body: JSON.stringify({
        code: 'EXPORT_FILE_EXPIRED',
        message: '导出文件已过期',
        data: {},
        trace_id: 'xyz-456',
      }),
    });

    await expect(downloadExportJob(succeededJob)).rejects.toMatchObject({
      code: 'EXPORT_FILE_EXPIRED',
      message: '导出文件已过期',
      status: 410,
      traceId: 'xyz-456',
    });
  });

  it('503 网关文本错误抛普通文本 ApiError（正文保留摘要）', async () => {
    stubDownloadResponse({ ok: false, status: 503, body: 'Service Unavailable' });

    await expect(downloadExportJob(succeededJob)).rejects.toMatchObject({
      message: expect.stringContaining('Service Unavailable'),
      status: 503,
    });
  });
});

describe('EXPORT_COLUMN_OPTIONS', () => {
  it('共 9 列，默认勾选 6 列且顺序与导出列白名单一致', () => {
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
