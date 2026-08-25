import { afterEach, describe, expect, it, vi } from 'vitest';
import { listOrders, type OrderPage } from './api';

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

/** 构造一个符合 be-td.md 4.3 响应格式的分页 Envelope。 */
function successEnvelope(data: OrderPage) {
  return { code: 'SUCCESS', message: null, data, trace_id: 't-1' };
}

const samplePage: OrderPage = {
  items: [
    {
      id: 1,
      order_no: 'PERF-000001',
      order_status: 'PAID',
      sales_channel: 'WEB',
      customer_name: '张三',
      total_amount: 89.19,
      currency: 'CNY',
      created_at: '2026-08-01T08:30:00',
    },
  ],
  page: 1,
  page_size: 20,
  total: 57,
  total_pages: 3,
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('listOrders', () => {
  it('默认参数构建 page/page_size 并解包 Envelope 的 data', async () => {
    stubFetch(successEnvelope(samplePage));
    const result = await listOrders({ page: 1, pageSize: 20 });

    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
    const [url] = vi.mocked(fetch).mock.calls[0] as [string];
    expect(url).toBe('/api/v1/orders?page=1&page_size=20');
    // 返回的是解包后的 data，不含 code/message/trace_id 外壳
    expect(result).toEqual(samplePage);
  });

  it('空参数时不拼接 query，路径不带尾随问号', async () => {
    const mock = stubFetch(successEnvelope({ ...samplePage, items: [] }));
    await listOrders();
    const url = mock.mock.calls[0][0];
    expect(url).toBe('/api/v1/orders');
  });

  it('完整的筛选 + 排序参数全部拼进 query（snake_case）', async () => {
    const mock = stubFetch(successEnvelope(samplePage));
    await listOrders({
      page: 2,
      pageSize: 50,
      orderNo: 'A001',
      orderStatus: 'PAID',
      salesChannel: 'WEB',
      createdFrom: '2026-08-01T00:00:00Z',
      createdTo: '2026-08-02T00:00:00Z',
      minAmount: 10,
      maxAmount: 100,
      sortBy: 'total_amount',
      sortOrder: 'desc',
    });
    const url = mock.mock.calls[0][0] as string;
    expect(url).toBe(
      '/api/v1/orders?page=2&page_size=50&order_no=A001&order_status=PAID' +
        '&sales_channel=WEB&created_from=2026-08-01T00%3A00%3A00Z' +
        '&created_to=2026-08-02T00%3A00%3A00Z&min_amount=10' +
        '&max_amount=100&sort_by=total_amount&sort_order=desc',
    );
  });

  it('只传个别可选参数时，未传的字段不出现在 query 中', async () => {
    const mock = stubFetch(successEnvelope(samplePage));
    await listOrders({ orderStatus: 'CANCELED' });
    const url = mock.mock.calls[0][0] as string;
    // 仅有 order_status，不应出现 sort_by / sales_channel / page 等
    expect(url).toBe('/api/v1/orders?order_status=CANCELED');
  });

  it('order_no 去除首尾空白后再拼接', async () => {
    const mock = stubFetch(successEnvelope(samplePage));
    await listOrders({ orderNo: '  A001  ' });
    const url = mock.mock.calls[0][0] as string;
    expect(url).toBe('/api/v1/orders?order_no=A001');
  });

  it('HTTP 非 2xx 时抛出带 status 与 trace_id 的 ApiError', async () => {
    stubFetch({ code: 'INTERNAL_ERROR', message: '服务器开小差了', data: null, trace_id: 't-500' }, 500);
    await expect(listOrders({ page: 1 })).rejects.toMatchObject({
      code: 'INTERNAL_ERROR',
      traceId: 't-500',
      status: 500,
    });
  });

  it('业务校验错误（400 VALIDATION_ERROR）抛出对应错误码', async () => {
    stubFetch(
      { code: 'VALIDATION_ERROR', message: 'page_size 必须在 1-100 之间', data: null, trace_id: 't-400' },
      400,
    );
    await expect(listOrders({ pageSize: 1000 })).rejects.toMatchObject({ code: 'VALIDATION_ERROR', status: 400 });
  });
});