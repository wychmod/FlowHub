// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { PropsWithChildren } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ExportJobEvent, ExportJobItem, ExportJobPage } from '../../api/exportApi';
import { useExportEvents } from './useExportEvents';

/** node 测试用 FakeEventSource 替换全局 EventSource：手动触发 open/fail/emit 驱动状态机。 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readonly listeners = new Map<string, Set<(event: MessageEvent<string>) => void>>();
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, listener: EventListenerOrEventListenerObject) {
    const callback = listener as (event: MessageEvent<string>) => void;
    const listeners = this.listeners.get(name) ?? new Set();
    listeners.add(callback);
    this.listeners.set(name, listeners);
  }

  close() {
    this.closed = true;
  }

  open() {
    this.onopen?.();
  }

  fail() {
    this.onerror?.();
  }

  emit(name: string, event: ExportJobEvent) {
    this.listeners.get(name)?.forEach((listener) => listener(new MessageEvent(name, {
      data: JSON.stringify(event),
    })));
  }
}

/** 构造一个默认缓存页（单条 RUNNING 任务），字段与后端列表契约对齐。 */
function jobPage(overrides: Partial<ExportJobItem> = {}): ExportJobPage {
  const item: ExportJobItem = {
    job_id: 42,
    job_no: 'EXP-J',
    status: 'RUNNING',
    job_version: 0,
    processed_rows: 0,
    total_rows: 1,
    progress_percent: 0,
    downloadable: false,
    file_size_bytes: null,
    error_code: null,
    error_message: null,
    created_at: '2026-08-04T00:00:00',
    finished_at: null,
    expired_at: null,
    ...overrides,
  };
  return { items: [item], page: 1, page_size: 20, total: 1 };
}

/** 事件序列：job_id 用字符串（后端 SSE payload 契约），version/status 等对齐。 */
function progressEvent(overrides: Partial<ExportJobEvent>): ExportJobEvent {
  return {
    job_id: '42',
    job_version: 1,
    status: 'RUNNING',
    processed_rows: 0,
    total_rows: 1,
    progress_percent: 0,
    occurred_at: '2026-08-04T00:00:01Z',
    ...overrides,
  };
}

describe('useExportEvents', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    vi.useFakeTimers();
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(['exportJobs', { page: 1 }], jobPage());
  });

  afterEach(() => {
    queryClient.clear();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('乱序事件不倒退：旧版本事件后到，缓存保持新版本终态', () => {
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    renderHook(() => useExportEvents(true), { wrapper });
    const fake = FakeEventSource.instances[0];

    act(() => {
      fake.open();
      fake.emit('job.succeeded', progressEvent({
        job_version: 2,
        status: 'SUCCEEDED',
        processed_rows: 1,
        progress_percent: 100,
        downloadable: true,
      }));
      fake.emit('job.progress', progressEvent({
        job_version: 1,
        status: 'RUNNING',
        processed_rows: 0,
        progress_percent: 0,
      }));
    });

    const cached = queryClient.getQueryData<ExportJobPage>(['exportJobs', { page: 1 }]);
    expect(cached?.items[0]).toMatchObject({
      status: 'SUCCEEDED',
      job_version: 2,
      downloadable: true,
    });
  });

  it('连续 3 次失败切 polling，且每个失败 source 都被关闭', () => {
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(() => useExportEvents(true), { wrapper });

    act(() => FakeEventSource.instances[0].fail());
    expect(FakeEventSource.instances[0].closed).toBe(true);
    act(() => vi.advanceTimersByTime(1_000));
    act(() => FakeEventSource.instances[1].fail());
    act(() => vi.advanceTimersByTime(2_000));
    act(() => FakeEventSource.instances[2].fail());

    expect(result.current).toMatchObject({ mode: 'polling', consecutiveFailures: 3 });
  });

  it('退避时序 1/2/5/10s，超出取末位', () => {
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(() => useExportEvents(true), { wrapper });
    const delays = [1_000, 2_000, 5_000, 10_000, 10_000];

    delays.forEach((delay, failureIndex) => {
      act(() => FakeEventSource.instances[failureIndex].fail());
      expect(FakeEventSource.instances[failureIndex].closed).toBe(true);
      if (failureIndex === 2) expect(result.current.mode).toBe('polling');
      act(() => vi.advanceTimersByTime(delay - 1));
      expect(FakeEventSource.instances).toHaveLength(failureIndex + 1);
      act(() => vi.advanceTimersByTime(1));
      expect(FakeEventSource.instances).toHaveLength(failureIndex + 2);
    });
  });

  it('状态筛选缓存：事件状态不匹配时移除该行', () => {
    const runningKey = ['exportJobs', { page: 1, status: 'RUNNING' }];
    queryClient.setQueryData(runningKey, jobPage());
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    renderHook(() => useExportEvents(true), { wrapper });

    act(() => FakeEventSource.instances[0].emit('job.failed', progressEvent({
      job_version: 3,
      status: 'FAILED',
      progress_percent: 100,
      error_code: 'FAILED',
      error_message: 'failed',
    })));

    expect(queryClient.getQueryData<ExportJobPage>(runningKey)?.items).toEqual([]);
  });

  it('错误字段存在性语义：显式 null 清空、缺失保留旧值', () => {
    const failed = jobPage({
      status: 'FAILED',
      job_version: 4,
      error_code: 'FILE_WRITE_FAILED',
      error_message: 'Disk full',
    });
    const key = ['exportJobs', { page: 1 }];
    queryClient.setQueryData(key, failed);
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    renderHook(() => useExportEvents(true), { wrapper });

    // 显式 error_code:null → 清空；缺少 error_message → 保留旧值
    act(() => FakeEventSource.instances[0].emit('job.progress', progressEvent({
      job_version: 5,
      status: 'PENDING',
      progress_percent: 0,
      error_code: null,
    })));

    expect(queryClient.getQueryData<ExportJobPage>(key)?.items[0]).toMatchObject({
      status: 'PENDING',
      error_code: null,
      error_message: 'Disk full',
    });
  });

  it('卸载后旧 Timer 不再切换 source（mountedRef 双保险）', () => {
    const { unmount } = renderHook(() => useExportEvents(true), {
      wrapper: ({ children }: PropsWithChildren) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    });

    unmount();
    // 卸载后 fail 一次触发 onerror → 内部 closeSource，但因 mountedRef=false 不再排重连
    act(() => FakeEventSource.instances[0].fail());
    act(() => vi.advanceTimersByTime(20_000));
    // 重连被抑制，无新 source
    expect(FakeEventSource.instances).toHaveLength(1);
  });
});