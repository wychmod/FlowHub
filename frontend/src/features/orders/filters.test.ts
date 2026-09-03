import { describe, expect, it } from 'vitest';
import dayjs from 'dayjs';
import { buildFilterSnapshot, formValuesToFilter } from './filters';

describe('formValuesToFilter', () => {
  it('全空表单折叠为空对象', () => {
    expect(formValuesToFilter({})).toEqual({});
  });

  it('空白串与空数组视为未传，对应字段不出现在结果', () => {
    const result = formValuesToFilter({
      orderNo: '   ',
      customerName: '',
      customerPhone: ' ',
      orderStatus: [],
      salesChannel: [],
      currency: [],
      totalAmountMin: null,
      totalAmountMax: null,
    });
    expect(result).toEqual({});
  });

  it('字符串 trim 后保留', () => {
    const result = formValuesToFilter({ orderNo: ' EF2026- ', customerPhone: '13900007919' });
    expect(result).toEqual({ orderNo: 'EF2026-', customerPhone: '13900007919' });
  });

  it('多值数组原样保留', () => {
    const result = formValuesToFilter({ orderStatus: ['PAID', 'SHIPPED'], currency: ['CNY', 'USD'] });
    expect(result).toEqual({ orderStatus: ['PAID', 'SHIPPED'], currency: ['CNY', 'USD'] });
  });

  it('金额单端合法（只填下界，上界 null 折叠）', () => {
    const result = formValuesToFilter({ totalAmountMin: 100, totalAmountMax: null });
    expect(result).toEqual({ totalAmountMin: 100 });
  });

  it('完整时间区间拆为两端 Dayjs', () => {
    const begin = dayjs('2026-01-01T00:00:00');
    const end = dayjs('2026-07-01T00:00:00');
    const result = formValuesToFilter({ createdAtRange: [begin, end] });
    expect(result).toEqual({ createdAtBegin: begin, createdAtEnd: end });
  });

  it('时间区间含 null 端时仅保留有效端', () => {
    const begin = dayjs('2026-01-01T00:00:00');
    const result = formValuesToFilter({ createdAtRange: [begin, null] });
    expect(result).toEqual({ createdAtBegin: begin });
  });

  it('createdAtRange 为 null 时完全折叠', () => {
    expect(formValuesToFilter({ createdAtRange: null })).toEqual({});
  });
});

describe('buildFilterSnapshot', () => {
  it('空 filter 只含排序两字段', () => {
    expect(buildFilterSnapshot({}, { field: 'created_at', direction: 'desc' })).toEqual({
      sort_by: 'created_at',
      sort_order: 'desc',
    });
  });

  it('完整 filter 映射为 snake_case 快照（多值为数组、时间本地格式字符串）', () => {
    const snapshot = buildFilterSnapshot(
      {
        orderStatus: ['PAID', 'SHIPPED'],
        salesChannel: ['WEB'],
        currency: ['CNY'],
        orderNo: 'EF2026-',
        customerName: '张',
        customerPhone: '13900007919',
        totalAmountMin: 100,
        totalAmountMax: 5000,
        createdAtBegin: dayjs('2026-01-01T08:30:00'),
        createdAtEnd: dayjs('2026-07-01T23:59:59'),
      },
      { field: 'total_amount', direction: 'asc' },
    );

    expect(snapshot).toEqual({
      order_status: ['PAID', 'SHIPPED'],
      sales_channel: ['WEB'],
      currency: ['CNY'],
      customer_name: '张',
      order_no: 'EF2026-',
      customer_phone: '13900007919',
      total_amount_min: 100,
      total_amount_max: 5000,
      created_at_begin: '2026-01-01T08:30:00',
      created_at_end: '2026-07-01T23:59:59',
      sort_by: 'total_amount',
      sort_order: 'asc',
    });
  });

  it('时间为本地格式，无时区后缀（禁止 toISOString 的 Z 后缀）', () => {
    const snapshot = buildFilterSnapshot(
      { createdAtBegin: dayjs('2026-01-01T08:30:00') },
      { field: 'created_at', direction: 'desc' },
    );
    expect(snapshot.created_at_begin).toBe('2026-01-01T08:30:00');
  });

  it('空数组与空串字段不出现在快照中', () => {
    const snapshot = buildFilterSnapshot(
      { orderStatus: [], customerName: '' },
      { field: 'created_at', direction: 'desc' },
    );
    expect(snapshot).toEqual({ sort_by: 'created_at', sort_order: 'desc' });
  });
});
