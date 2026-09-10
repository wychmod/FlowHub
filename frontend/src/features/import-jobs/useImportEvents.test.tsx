// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { PropsWithChildren } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ImportJobEvent, ImportJobItem, ImportJobPage } from '../../api/importApi';
import { useImportEvents } from './useImportEvents';

/** node/jsdom 测试用 FakeEventSource 替换全局 EventSource：手动触发 open/fail/emit 驱动状态机。 */
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

  emit(name: string, event: ImportJobEvent) {
    this.listeners.get(name)?.forEach((listener) => listener(new MessageEvent(name, {
      data: JSON.stringify(event),
    })));
  }
}

/** 构造一个默认缓存页（单条 RUNNING 任务），字段与后端列表契约对齐。 */
function jobPage(overrides: Partial<ImportJobItem> = {}): ImportJobPage {
  const item: ImportJobItem = {
    job_id: 42,
    job_no: 'IMP-J',
    status: 'RUNNING',
    job_version: 0,
    total_rows: 10,
    processed_rows: 0,
    succeeded_rows: 0,
    skipped_rows: 0,
    progress_percent: 0,
    error_report_available: false,
    created_at: '2026-09-09T00:00:00',
    ...overrides,
  };
  return { items: [item], page: 1, page_size: 20, total: 1 };
}

/** 事件序列：job_id 用字符串（后端 SSE payload 契约），version/status 等对齐。 */
function importEvent(overrides: Partial<ImportJobEvent>): ImportJobEvent {
  return {
    job_id: '42',
    job_version: 1,
    status: 'RUNNING',
    processed_rows: 0,
    total_rows: 10,
    progress_percent: 0,
    occurred_at: '2026-09-09T00:00:01Z',
    ...overrides,
  };
}

describe('useImportEvents', () => {
  let queryClient: QueryClient;
  const wrapper = ({ children }: PropsWithChildren) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );

  beforeEach(() => {
    vi.useFakeTimers();
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(['importJobs', { page: 1 }], jobPage());
  });

  afterEach(() => {
    queryClient.clear();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('乱序事件不倒退：旧版本事件后到，缓存保持新版本终态', () => {
    renderHook(() => useImportEvents(true), { wrapper });
    const fake = FakeEventSource.instances[0];
    act(() => {
      fake.open();
      fake.emit('import.succeeded', importEvent({
        job_version: 2,
        status: 'SUCCEEDED',
        processed_rows: 10,
        succeeded_rows: 10,
        skipped_rows: 0,
        progress_percent: 100,
      }));
      fake.emit('import.progress', importEvent({ job_version: 1, processed_rows: 0, progress_percent: 0 }));
    });
    const cached = queryClient.getQueryData<ImportJobPage>(['importJobs', { page: 1 }]);
    expect(cached?.items[0]).toMatchObject({ status: 'SUCCEEDED', job_version: 2, succeeded_rows: 10 });
  });

  it('import.partial 事件：更新为部分成功并点亮错误报告可用', () => {
    renderHook(() => useImportEvents(true), { wrapper });
    const fake = FakeEventSource.instances[0];
    act(() => fake.emit('import.partial', importEvent({
      job_version: 6,
      status: 'PARTIAL',
      processed_rows: 100,
      total_rows: 100,
      succeeded_rows: 90,
      skipped_rows: 10,
      progress_percent: 100,
      error_report_available: true,
    })));
    expect(queryClient.getQueryData<ImportJobPage>(['importJobs', { page: 1 }])?.items[0]).toMatchObject({
      status: 'PARTIAL',
      succeeded_rows: 90,
      skipped_rows: 10,
      error_report_available: true,
    });
  });

  it('事件未携带计数字段时保留旧值（字段存在性即协议）', () => {
    const key = ['importJobs', { page: 1 }];
    queryClient.setQueryData(key, jobPage({ succeeded_rows: 90, skipped_rows: 10 }));
    renderHook(() => useImportEvents(true), { wrapper });
    act(() => FakeEventSource.instances[0].emit('import.progress', importEvent({
      job_version: 7,
      processed_rows: 95,
    })));
    expect(queryClient.getQueryData<ImportJobPage>(key)?.items[0]).toMatchObject({
      processed_rows: 95,
      succeeded_rows: 90,
      skipped_rows: 10,
    });
  });

  it('错误字段存在性语义：显式 null 清空、缺失保留旧值', () => {
    const key = ['importJobs', { page: 1 }];
    queryClient.setQueryData(key, jobPage({
      status: 'FAILED',
      job_version: 4,
      error_code: 'IMPORT_SAX_FAILED',
      error_message: 'parse error',
    }));
    renderHook(() => useImportEvents(true), { wrapper });
    // 显式 error_code:null → 清空；缺少 error_message → 保留旧值
    act(() => FakeEventSource.instances[0].emit('import.progress', importEvent({
      job_version: 5,
      status: 'PENDING',
      error_code: null,
    })));
    expect(queryClient.getQueryData<ImportJobPage>(key)?.items[0]).toMatchObject({
      status: 'PENDING',
      error_code: null,
      error_message: 'parse error',
    });
  });

  it('状态筛选缓存：事件状态不匹配时移除该行', () => {
    const runningKey = ['importJobs', { page: 1, status: 'RUNNING' }];
    queryClient.setQueryData(runningKey, jobPage());
    renderHook(() => useImportEvents(true), { wrapper });
    act(() => FakeEventSource.instances[0].emit('import.failed', importEvent({
      job_version: 3,
      status: 'FAILED',
      progress_percent: 100,
      error_code: 'IMPORT_FAILED',
      error_message: 'boom',
    })));
    expect(queryClient.getQueryData<ImportJobPage>(runningKey)?.items).toEqual([]);
  });

  it('连续 3 次失败切 polling', () => {
    const { result } = renderHook(() => useImportEvents(true), { wrapper });
    act(() => FakeEventSource.instances[0].fail());
    expect(FakeEventSource.instances[0].closed).toBe(true);
    act(() => vi.advanceTimersByTime(1_000));
    act(() => FakeEventSource.instances[1].fail());
    act(() => vi.advanceTimersByTime(2_000));
    act(() => FakeEventSource.instances[2].fail());
    expect(result.current).toMatchObject({ mode: 'polling', consecutiveFailures: 3 });
  });

  it('卸载后旧 Timer 不再切换 source（mountedRef 双保险）', () => {
    const { unmount } = renderHook(() => useImportEvents(true), { wrapper });
    unmount();
    act(() => FakeEventSource.instances[0].fail());
    act(() => vi.advanceTimersByTime(20_000));
    expect(FakeEventSource.instances).toHaveLength(1);
  });
});
