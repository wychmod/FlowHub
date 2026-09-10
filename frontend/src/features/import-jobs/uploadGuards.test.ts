import { describe, expect, it } from 'vitest';
import { checkUploadFile } from './uploadGuards';

const MB = 1024 * 1024;

describe('checkUploadFile', () => {
  it('合法 .xlsx 通过', () => {
    expect(checkUploadFile({ name: 'orders.xlsx', size: 2 * MB })).toMatchObject({ ok: true });
  });

  it('后缀大小写不敏感（.XLSX 通过）', () => {
    expect(checkUploadFile({ name: 'ORDERS.XLSX', size: MB })).toMatchObject({ ok: true });
  });

  it('拒绝 .xls / .csv / 无后缀', () => {
    for (const name of ['a.xls', 'a.csv', 'orders', 'a.xlsm']) {
      expect(checkUploadFile({ name, size: MB })).toMatchObject({ ok: false, reason: 'FORMAT' });
    }
  });

  it('超过 10MB 拒绝、恰好 10MB 通过', () => {
    expect(checkUploadFile({ name: 'a.xlsx', size: 10 * MB + 1 })).toMatchObject({
      ok: false,
      reason: 'TOO_LARGE',
    });
    expect(checkUploadFile({ name: 'a.xlsx', size: 10 * MB })).toMatchObject({ ok: true });
  });
});
