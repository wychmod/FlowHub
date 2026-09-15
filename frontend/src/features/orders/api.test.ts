import { afterEach, describe, expect, it, vi } from 'vitest';
import dayjs from 'dayjs';
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

/** 构造一个符合分页响应格式的 Envelope。 */
function successEnvelope(data: OrderPage) {
  return { code: 'SUCCESS', message: null, data, trace_id: 't-1' };
}

const samplePage: OrderPage = {
  items: [
    {
      id: 1,
      order_no: 'EF2026-00000001',
      order_status: 'PAID',
      sales_channel: 'WEB',
      customer_name: '张伟',
      customer_phone: '13900007919',
      shipping_province: '四川省',
      total_amount: 89.19,
      currency: 'CNY',
      created_at: '2025-09-01T02:46:13',
    },
  ],
  page: 1,
  page_size: 10,
  total: 57,
  total_pages: 6,
  sort_by: 'created_at',
  sort_order: 'desc',
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('listOrders', () => {
  it('构建后端支持的 page/page_size 参数并解包 Envelope 的 data', async () => {
    stubFetch(successEnvelope(samplePage));
    const result = await listOrders({ page: 1, pageSize: 10 });

    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
    const [url] = vi.mocked(fetch).mock.calls[0] as [string];
    expect(url).toBe('/api/v1/orders?page=1&page_size=10');
    // 返回的是解包后的 data，不含 code/message/trace_id 外壳
    expect(result).toEqual(samplePage);
  });

  it('空参数时不拼接 query，路径不带尾随问号', async () => {
    const mock = stubFetch(successEnvelope({ ...samplePage, items: [] }));
    await listOrders();
    const url = mock.mock.calls[0][0];
    expect(url).toBe('/api/v1/orders');
  });

  it('序列化完整筛选与排序契约（snake_case + 多值逗号拼接 + 时间本地格式）', async () => {
    const mock = stubFetch(successEnvelope(samplePage));
    await listOrders({
      page: 1,
      pageSize: 20,
      orderStatus: ['PAID', 'SHIPPED'],
      salesChannel: ['WEB'],
      currency: ['CNY'],
      customerName: '张',
      orderNo: 'EF2026-',
      customerPhone: '13900007919',
      totalAmountMin: 100,
      totalAmountMax: 5000,
      createdAtBegin: '2026-01-01T00:00:00',
      createdAtEnd: '2026-07-01T00:00:00',
      sort: { field: 'total_amount', direction: 'desc' },
    });

    const url = mock.mock.calls[0][0] as string;
    // URLSearchParams 按标准对 ',' 与 ':' 转义（%2C / %3A），后端会正确解码
    expect(url).toBe(
      '/api/v1/orders?page=1&page_size=20'
        + '&order_status=PAID%2CSHIPPED'
        + '&sales_channel=WEB'
        + '&currency=CNY'
        + '&customer_name=%E5%BC%A0'
        + '&order_no=EF2026-'
        + '&customer_phone=13900007919'
        + '&total_amount_min=100'
        + '&total_amount_max=5000'
        + '&created_at_begin=2026-01-01T00%3A00%3A00'
        + '&created_at_end=2026-07-01T00%3A00%3A00'
        + '&sort_by=total_amount&sort_order=desc',
    );
  });

  it('时间参数传 dayjs 对象时按本地格式序列化（无时区后缀）', async () => {
    const mock = stubFetch(successEnvelope(samplePage));
    await listOrders({
      createdAtBegin: dayjs('2026-01-01T08:30:00'),
      createdAtEnd: dayjs('2026-07-01T23:59:59'),
    });

    const url = mock.mock.calls[0][0] as string;
    expect(url).toContain('created_at_begin=2026-01-01T08%3A30%3A00');
    expect(url).toContain('created_at_end=2026-07-01T23%3A59%3A59');
    // 时间契约：绝不能出现 toISOString() 的 UTC Z 后缀
    expect(url).not.toContain('%3A00.000Z');
  });

  it('只传排序字段时不带 sort_order（由后端按字段默认方向展开）', async () => {
    const mock = stubFetch(successEnvelope(samplePage));
    await listOrders({ sort: { field: 'order_no' } });

    const url = mock.mock.calls[0][0] as string;
    expect(url).toBe('/api/v1/orders?sort_by=order_no');
  });

  it('空数组与空白字符串参数视为未传，不拼进 query', async () => {
    const mock = stubFetch(successEnvelope(samplePage));
    await listOrders({
      page: 1,
      orderStatus: [],
      salesChannel: [],
      currency: [],
      customerName: '   ',
      customerPhone: '',
    });

    const url = mock.mock.calls[0][0] as string;
    expect(url).toBe('/api/v1/orders?page=1');
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
