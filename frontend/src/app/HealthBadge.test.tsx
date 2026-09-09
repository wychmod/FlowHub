// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fetchHealth, type HealthReport } from '../api/healthApi';
import { healthBadgeTheme, healthComponentsText, HealthBadge } from './HealthBadge';

vi.mock('../api/healthApi', () => ({ fetchHealth: vi.fn() }));
const fetchHealthMock = vi.mocked(fetchHealth);

/** 每个用例独立的 QueryClient，避免缓存跨用例串扰。 */
function renderWithQuery(ui: ReactElement) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('healthBadgeTheme', () => {
  it('请求失败优先于缓存数据：展示服务不可达', () => {
    const report: HealthReport = { status: 'UP' };
    expect(healthBadgeTheme(report, true)).toEqual({ status: 'error', text: '服务不可达' });
  });

  it('无数据时为检测中', () => {
    expect(healthBadgeTheme(undefined, false)).toEqual({ status: 'processing', text: '检测中…' });
  });

  it('整体 UP 为服务正常', () => {
    expect(healthBadgeTheme({ status: 'UP' }, false)).toEqual({ status: 'success', text: '服务正常' });
  });

  it('整体非 UP（含组件 DOWN 拖累聚合）为服务异常', () => {
    expect(healthBadgeTheme({ status: 'DOWN' }, false)).toEqual({ status: 'error', text: '服务异常' });
  });
});

describe('healthComponentsText', () => {
  it('无 components 时提示降级语义', () => {
    expect(healthComponentsText(undefined)).toBe('组件详情未开放，仅整体状态可见');
    expect(healthComponentsText({ status: 'UP' })).toBe('组件详情未开放，仅整体状态可见');
  });

  it('有 components 时逐项输出 名称: 状态', () => {
    const text = healthComponentsText({
      status: 'DOWN',
      components: {
        db: { status: 'UP' },
        rabbit: { status: 'DOWN' },
        redis: { status: 'UP' },
      },
    });
    expect(text).toBe('db: UP，rabbit: DOWN，redis: UP');
  });
});

describe('HealthBadge 组件', () => {
  it('后端 UP 时展示「服务正常」', async () => {
    fetchHealthMock.mockResolvedValue({ status: 'UP', components: { db: { status: 'UP' } } });
    renderWithQuery(<HealthBadge />);
    await waitFor(() => expect(screen.getByText('服务正常')).toBeTruthy());
  });

  it('请求失败时展示「服务不可达」', async () => {
    // 组件自带 retry: 1（约 1s 退避），等待窗口需覆盖重试周期
    fetchHealthMock.mockRejectedValue(new Error('connection refused'));
    renderWithQuery(<HealthBadge />);
    await waitFor(() => expect(screen.getByText('服务不可达')).toBeTruthy(), { timeout: 4000 });
  });
});
