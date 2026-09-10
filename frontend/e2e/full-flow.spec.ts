import { test, expect, type Page } from '@playwright/test';
import { makeImportXlsx } from './helpers';

/**
 * 全功能浏览器 E2E（串行）：订单页（筛选/排序/翻页/勾选）→ 导出创建全链路（SSE 实时进度）→
 * 下载 → 导入弹窗（成功/模板不符失败）→ 导入任务页。每个用例断言无未捕获 JS 错误。
 */

const RUN = Math.random().toString(36).slice(2, 8).toUpperCase();

/**
 * 收集未捕获异常与 console error（页面崩溃/接口异常链路都会体现在这里）。
 * 白名单：favicon 404；失败路径用例故意触发的业务接口 4xx（页面有结构化错误处理）。
 */
function trackErrors(page: Page): () => string[] {
  const errors: string[] = [];
  page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const url = msg.location()?.url ?? '';
    if (url.includes('favicon')) return;
    errors.push(`console.error: ${msg.text()} (${url})`);
  });
  return () =>
    errors.filter(
      (e) =>
        !e.includes('favicon')
        && !/Failed to load resource.*(400|409|404) \(.*\/api\/v1\//.test(e),
    );
}

test.describe.serial('全功能链路', () => {
  let checkErrors: () => string[];

  test('订单页加载与健康徽标', async ({ page }) => {
    checkErrors = trackErrors(page);
    await page.goto('/');
    await expect(page.getByText('订单列表').first()).toBeVisible();
    await expect(page.locator('.orders-table tr.ant-table-row').first()).toBeVisible({ timeout: 20_000 });
    // 顶栏健康徽标收敛到「服务正常」（15s 轮询，首帧可能为检测中）
    await expect(page.getByText('服务正常')).toBeVisible({ timeout: 20_000 });
    const errors = checkErrors().filter((e) => !e.includes('favicon'));
    expect(errors, errors.join('\n')).toEqual([]);
  });

  test('条件筛选：订单号模糊 + 重置恢复', async ({ page }) => {
    checkErrors = trackErrors(page);
    await page.goto('/');
    await page.locator('.orders-table tr.ant-table-row').first().waitFor();
    await page.getByRole('textbox', { name: '订单号' }).fill('EF2026');
    await page.getByRole('button', { name: /查询/ }).click();
    await expect(page.locator('.orders-table tr.ant-table-row').first())
      .toContainText('EF2026', { timeout: 20_000 });
    // 重置后回到全量（默认 pageSize=10）
    await page.getByRole('button', { name: /重\s*置/ }).click();
    await expect(page.locator('.orders-table tr.ant-table-row')).toHaveCount(10, { timeout: 20_000 });
    expect(checkErrors().filter((e) => !e.includes('favicon'))).toEqual([]);
  });

  test('排序与翻页不报错', async ({ page }) => {
    checkErrors = trackErrors(page);
    await page.goto('/');
    await page.locator('.orders-table tr.ant-table-row').first().waitFor();
    const firstCell = () => page.locator('.orders-table tr.ant-table-row').first().locator('td').nth(1); // 第 1 个 td 是勾选列
    const beforeSort = await firstCell().textContent();
    await page.getByRole('columnheader', { name: /订单号/ }).click();
    await expect(page.locator('.orders-table tr.ant-table-row').first()).toBeVisible();
    await page.waitForTimeout(800); // 等排序响应完成
    const afterSort = await firstCell().textContent();
    expect(afterSort).not.toBe(beforeSort);
    // 翻页：点第 2 页
    await page.locator('.ant-pagination-item-2').click();
    await page.waitForTimeout(800);
    await expect(page.locator('.ant-pagination-item-2')).toHaveClass(/ant-pagination-item-active/);
    expect(checkErrors().filter((e) => !e.includes('favicon'))).toEqual([]);
  });

  test('勾选 2 行 → 导出已选 → 实时进度到已完成 → 下载', async ({ page }) => {
    checkErrors = trackErrors(page);
    await page.goto('/');
    const rows = page.locator('.orders-table tr.ant-table-row');
    await rows.first().waitFor();
    await rows.nth(0).locator('.ant-checkbox-input').check();
    await rows.nth(1).locator('.ant-checkbox-input').check();
    await expect(page.getByText('已选择 2 条')).toBeVisible();

    await page.getByRole('button', { name: '导出已选' }).click();
    const modal = page.getByRole('dialog');
    await expect(modal).toBeVisible();
    await modal.getByRole('button', { name: '创建任务' }).click();
    // 成功后弹窗 footer 换为「查看导入任务」路径（导出为「查看任务」）
    await expect(modal.getByRole('button', { name: /查看任务/ })).toBeVisible({ timeout: 20_000 });
    await modal.getByRole('button', { name: /查看任务/ }).click();

    // 导出任务页：新任务出现并经 SSE 推进到已完成
    await expect(page.getByText('导出任务').first()).toBeVisible();
    const doneRow = page.locator('tbody tr').filter({ hasText: '已完成' }).first();
    await expect(doneRow).toBeVisible({ timeout: 90_000 });

    // 下载已完成的任务
    const downloadPromise = page.waitForEvent('download');
    await doneRow.getByLabel(/^下载 /).click();
    const download = await downloadPromise;
    const path = await download.path();
    const fs = await import('node:fs');
    const head = fs.readFileSync(path).subarray(0, 2);
    expect(head.toString()).toBe('PK');
    expect(checkErrors().filter((e) => !e.includes('favicon'))).toEqual([]);
  });

  test('导出筛选结果创建成功', async ({ page }) => {
    checkErrors = trackErrors(page);
    await page.goto('/');
    await page.locator('.orders-table tr.ant-table-row').first().waitFor();
    await page.getByRole('button', { name: '导出筛选结果' }).click();
    const modal = page.getByRole('dialog');
    await expect(modal).toBeVisible();
    await modal.getByRole('button', { name: '创建任务' }).click();
    await expect(modal.getByRole('button', { name: /查看任务|留在本页/ }).first())
      .toBeVisible({ timeout: 20_000 });
    expect(checkErrors().filter((e) => !e.includes('favicon'))).toEqual([]);
  });

  test('导入订单：合法文件受理成功并跳转任务页', async ({ page }) => {
    checkErrors = trackErrors(page);
    await page.goto('/');
    await page.locator('.orders-table tr.ant-table-row').first().waitFor();
    const rows = [
      [`E2EF-${RUN}-A1`, 'PENDING', 'WEB', '浏览器客户甲', '13811111111', 66.6, 'CNY', '北京市', '2026-09-01 08:00:00'],
      [`E2EF-${RUN}-A2`, 'PAID', 'APP', '浏览器客户乙', '13822222222', 88.8, 'USD', '广东省', '2026-09-01 09:00:00'],
      [`E2EF-${RUN}-A3`, 'SHIPPED', 'STORE', '浏览器客户丙', '13833333333', 99.9, 'EUR', '浙江省', '2026-09-01 10:00:00'],
    ];
    const xlsx = makeImportXlsx(rows);
    await page.getByRole('button', { name: '导入订单' }).click();
    const modal = page.getByRole('dialog');
    await expect(modal).toBeVisible();
    await modal.locator('input[type=file]').setInputFiles({
      name: `e2e-${RUN}.xlsx`,
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: xlsx,
    });
    await expect(modal.getByText(/已选择/)).toBeVisible();
    await modal.getByRole('button', { name: '开始导入' }).click();
    await expect(modal.getByText('导入任务已创建')).toBeVisible({ timeout: 30_000 });
    await modal.getByRole('button', { name: '查看导入任务' }).click();
    await expect(page.getByText('导入任务').first()).toBeVisible();
    const importRow = page.locator('tbody tr').filter({ hasText: `e2e-${RUN}.xlsx` }).first();
    await expect(importRow).toBeVisible({ timeout: 20_000 });
    // 等行级异步导入收敛到终态（SSE 实时推进），后续数据核对才有数据
    await expect(importRow.getByText('全部成功')).toBeVisible({ timeout: 60_000 });
    expect(checkErrors().filter((e) => !e.includes('favicon'))).toEqual([]);
  });

  test('导入失败路径：表头不符弹窗保留并展示错误', async ({ page }) => {
    checkErrors = trackErrors(page);
    await page.goto('/');
    await page.locator('.orders-table tr.ant-table-row').first().waitFor();
    const bad = makeImportXlsx([[`E2EF-${RUN}-BAD`, 'PENDING', 'WEB', '错列', '138', 1, 'CNY', '上海市', '2026-09-01 08:00:00']],
      { header: ['订单号', '错误列'] });
    await page.getByRole('button', { name: '导入订单' }).click();
    const modal = page.getByRole('dialog');
    await modal.locator('input[type=file]').setInputFiles({
      name: 'bad-template.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: bad,
    });
    await modal.getByRole('button', { name: '开始导入' }).click();
    await expect(modal.getByText('导入受理失败')).toBeVisible({ timeout: 20_000 });
    await expect(modal.getByText(/模板不匹配/)).toBeVisible();
    // 弹窗保留可重试
    await expect(modal.getByRole('button', { name: '开始导入' })).toBeVisible();
    await modal.getByRole('button', { name: /取\s*消/ }).click();
    expect(checkErrors().filter((e) => !e.includes('favicon'))).toEqual([]);
  });

  test('导入数据核对：导入的订单出现在订单页筛选', async ({ page }) => {
    checkErrors = trackErrors(page);
    await page.goto('/');
    await page.locator('.orders-table tr.ant-table-row').first().waitFor();
    await page.getByRole('textbox', { name: '订单号' }).fill(`E2EF-${RUN}`);
    await page.getByRole('button', { name: /查询/ }).click();
    await expect(page.locator('.orders-table tr.ant-table-row').filter({ hasText: `E2EF-${RUN}` }).first())
      .toBeVisible({ timeout: 20_000 });
    expect(checkErrors().filter((e) => !e.includes('favicon'))).toEqual([]);
  });
});
