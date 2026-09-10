import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  downloadImportErrorReport,
  downloadImportTemplate,
  importEventsUrl,
  listImportJobs,
  MAX_UPLOAD_BYTES,
  retryImportJob,
  uploadOrderImport,
  type ImportJobItem,
} from './importApi';

/** 模拟一个返回 Envelope JSON 的 Response（list/retry/upload 只用到 ok/status/json）。 */
function stubJsonFetch(body: unknown, status = 200): ReturnType<typeof vi.fn> {
  const fake = {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
  const mock = vi.fn().mockResolvedValue(fake);
  vi.stubGlobal('fetch', mock);
  return mock;
}

function successEnvelope(data: unknown) {
  return { code: 'SUCCESS', message: null, data, trace_id: 't-1' };
}

/** 下载场景 Response 桩：成功二进制、失败可能是 JSON 错误包或网关文本。 */
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

const partialJob: ImportJobItem = {
  job_id: 42,
  job_no: 'IMP202609090001',
  status: 'PARTIAL',
  job_version: 6,
  total_rows: 100,
  processed_rows: 100,
  succeeded_rows: 90,
  skipped_rows: 10,
  progress_percent: 100,
  error_report_available: true,
  created_at: '2026-09-09T10:20:30',
  file_name: 'orders.xlsx',
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('listImportJobs', () => {
  it('GET 分页，携带 page/page_size，解包 Envelope 的 data', async () => {
    const page = { items: [partialJob], total: 1, page: 1, page_size: 20 };
    const mock = stubJsonFetch(successEnvelope(page));
    const result = await listImportJobs({ page: 1, pageSize: 20 });

    const [url] = mock.mock.calls[0] as [string];
    expect(url).toBe('/api/v1/import-jobs?page=1&page_size=20');
    expect(result).toEqual(page);
  });

  it('带 status 过滤时追加 status 查询参数', async () => {
    const mock = stubJsonFetch(successEnvelope({ items: [], total: 0, page: 1, page_size: 10 }));
    await listImportJobs({ page: 1, pageSize: 10, status: 'PARTIAL' });
    const [url] = mock.mock.calls[0] as [string];
    expect(url).toContain('status=PARTIAL');
  });
});

const accepted = {
  job_id: 42,
  job_no: 'IMP202609090001',
  status: 'PENDING',
  total_rows: 100,
  file_name: 'orders.xlsx',
  created_at: '2026-09-09T10:20:30',
};

describe('uploadOrderImport', () => {
  it('POST multipart：body 为 FormData（字段 file）、不手动设 Content-Type、带 Accept，解包 202', async () => {
    const mock = stubJsonFetch(successEnvelope(accepted), 202);
    const result = await uploadOrderImport(new Blob(['x']) as unknown as File);

    expect(mock).toHaveBeenCalledTimes(1);
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/import-jobs');
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('file')).toBeTruthy();
    const headers = init.headers as Record<string, string>;
    expect(headers['Content-Type']).toBeUndefined();
    expect(headers.Accept).toBe('application/json');
    expect(result).toEqual(accepted);
  });

  it('结构校验 400 时抛出带 code/trace_id/status 的 ApiError', async () => {
    stubJsonFetch(
      { code: 'IMPORT_TEMPLATE_MISMATCH', message: '模板不匹配', data: null, trace_id: 't-400' },
      400,
    );
    await expect(uploadOrderImport(new Blob(['x']) as unknown as File)).rejects.toMatchObject({
      code: 'IMPORT_TEMPLATE_MISMATCH',
      traceId: 't-400',
      status: 400,
    });
  });
});

describe('retryImportJob', () => {
  it('POST /import-jobs/{id}/retry 并解包 Envelope', async () => {
    const mock = stubJsonFetch(successEnvelope({ ...accepted, status: 'PENDING' }), 202);
    const result = await retryImportJob(42);
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/import-jobs/42/retry');
    expect(init.method).toBe('POST');
    expect(result).toMatchObject({ status: 'PENDING' });
  });

  it('非 FAILED / 达上限被拒时抛 409 IMPORT_JOB_NOT_RETRYABLE', async () => {
    stubJsonFetch(
      { code: 'IMPORT_JOB_NOT_RETRYABLE', message: '不可重试', data: null, trace_id: 't-409' },
      409,
    );
    await expect(retryImportJob(42)).rejects.toMatchObject({
      code: 'IMPORT_JOB_NOT_RETRYABLE',
      status: 409,
    });
  });
});

describe('downloadImportTemplate', () => {
  it('请求模板地址并携带 xlsx Accept，按响应头文件名保存', async () => {
    const mock = stubDownloadResponse({
      ok: true,
      status: 200,
      disposition: "attachment; filename*=UTF-8''%E6%A8%A1%E6%9D%BF.xlsx",
    });
    const dom = stubDownloadDom();
    await downloadImportTemplate();
    const [url, init] = mock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/import-jobs/template');
    expect(init.headers).toMatchObject({
      Accept: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    expect(dom.anchors[0].download).toBe('模板.xlsx');
  });

  it('下载失败（错误 Envelope）抛出带 code 的 ApiError', async () => {
    stubDownloadResponse({
      ok: false,
      status: 404,
      body: JSON.stringify({ code: 'IMPORT_JOB_NOT_FOUND', message: '不存在', data: {}, trace_id: 't' }),
    });
    await expect(downloadImportTemplate()).rejects.toMatchObject({
      code: 'IMPORT_JOB_NOT_FOUND',
      status: 404,
    });
  });
});

describe('downloadImportErrorReport', () => {
  it('请求错误报告地址，按 PARTIAL 任务编号兜底命名', async () => {
    stubDownloadResponse({ ok: true, status: 200 });
    const dom = stubDownloadDom();
    await downloadImportErrorReport(partialJob);
    expect(dom.anchors[0].download).toBe('导入错误报告-IMP202609090001.xlsx');
  });
});

describe('importEventsUrl / 常量', () => {
  it('SSE 端点与上传大小上限', () => {
    expect(importEventsUrl).toBe('/api/v1/import-jobs/events');
    expect(MAX_UPLOAD_BYTES).toBe(10 * 1024 * 1024);
  });
});
