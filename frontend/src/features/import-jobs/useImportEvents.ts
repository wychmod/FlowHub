import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { importEventsUrl, type ImportJobEvent, type ImportJobPage } from '../../api/importApi';
import type { ImportEventConnectionState } from './types';

/** 重连退避序列（ms）：连续失败后按 1s/2s/5s/10s 递增，超出取末位。与导出一致。 */
const RECONNECT_DELAYS = [1_000, 2_000, 5_000, 10_000];

/**
 * SSE 事件结构校验：确认是导入任务事件而非未知 payload（心跳事件名不经过此校验）。
 * 缺核心字段（job_id/job_version/status/processed_rows/total_rows/progress_percent）即判无效丢弃，
 * HTTP 校准仍为权威。succeeded_rows/skipped_rows 为可选（progress/partial 携带）。
 */
function isImportJobEvent(value: unknown): value is ImportJobEvent {
  if (!value || typeof value !== 'object') return false;
  const event = value as Partial<ImportJobEvent>;
  return typeof event.job_id === 'string'
    && Number.isSafeInteger(event.job_version)
    && event.job_version! >= 0
    && typeof event.status === 'string'
    && typeof event.processed_rows === 'number'
    && typeof event.total_rows === 'number'
    && typeof event.progress_percent === 'number';
}

/**
 * 消费导入任务 SSE 事件流：
 * 逐段复刻 useExportEvents，仅三处差异——queryKey ['importJobs']、事件名 import.*（含 import.partial）、
 * payload ImportJobEvent（乐观更新额外覆盖 succeeded_rows/skipped_rows/error_report_available）。
 *
 * @param enabled 页面是否启用 SSE（进入导入页传 true，离开传 false）
 */
export function useImportEvents(enabled: boolean): ImportEventConnectionState {
  const queryClient = useQueryClient();
  const [state, setState] = useState<ImportEventConnectionState>(() => ({
    mode: navigator.onLine ? 'connecting' : 'offline',
    consecutiveFailures: 0,
  }));
  const sourceRef = useRef<EventSource | null>(null);
  const reconnectTimerRef = useRef<number | undefined>(undefined);
  const failureCountRef = useRef(0);
  const mountedRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;

    const clearReconnect = () => {
      if (reconnectTimerRef.current !== undefined) {
        window.clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = undefined;
      }
    };

    const closeSource = () => {
      const source = sourceRef.current;
      sourceRef.current = null;
      source?.close();
    };

    const refetch = () => queryClient.invalidateQueries({ queryKey: ['importJobs'] });

    const applyEvent = (event: ImportJobEvent) => {
      const cachedQueries = queryClient.getQueriesData<ImportJobPage>({ queryKey: ['importJobs'] });
      // job_id 口径：列表接口为 number、SSE payload 为 string，统一转字符串比较
      const matchesJob = (jobId: number) => String(jobId) === event.job_id;
      // 版本栅栏：遍历所有缓存页，取同 job_id 已知最大 job_version，旧事件直接丢弃
      const knownVersions = cachedQueries.flatMap(([, page]) => (
        page?.items.filter((job) => matchesJob(job.job_id)).map((job) => job.job_version) ?? []
      ));
      if (knownVersions.length > 0 && event.job_version <= Math.max(...knownVersions)) return;
      cachedQueries.forEach(([queryKey, page]) => {
        if (!page) return;
        let changed = false;
        let removed = 0;
        const requestedStatus = (queryKey[1] as { status?: string } | undefined)?.status;
        const items = page.items.flatMap((job) => {
          if (!matchesJob(job.job_id) || event.job_version <= job.job_version) return job;
          changed = true;
          // 筛选缓存智能移除：事件状态不匹配当前 status 筛选 → 移除该行并修正 total
          if (requestedStatus && requestedStatus !== event.status) {
            removed += 1;
            return [];
          }
          return [{
            ...job,
            job_version: event.job_version,
            status: event.status,
            processed_rows: event.processed_rows,
            total_rows: event.total_rows,
            progress_percent: event.progress_percent,
            // 导入计数：事件携带则更新，缺失则保留旧值（字段存在性即协议）
            succeeded_rows: typeof event.succeeded_rows === 'number' ? event.succeeded_rows : job.succeeded_rows,
            skipped_rows: typeof event.skipped_rows === 'number' ? event.skipped_rows : job.skipped_rows,
            error_report_available: typeof event.error_report_available === 'boolean'
              ? event.error_report_available : job.error_report_available,
            // 错误字段：缺失 → 保留旧值；显式 null → 清空（hasOwnProperty 语义，与导出一致）
            error_code: Object.prototype.hasOwnProperty.call(event, 'error_code')
              ? event.error_code ?? null : job.error_code,
            error_message: Object.prototype.hasOwnProperty.call(event, 'error_message')
              ? event.error_message ?? null : job.error_message,
          }];
        });
        if (!changed) return;
        queryClient.setQueryData<ImportJobPage>(queryKey, {
          ...page,
          items,
          total: Math.max(0, page.total - removed),
        });
      });
      setState((current) => ({ ...current, lastEventAt: Date.now() }));
      // 失效收敛：让服务端重新计算分页/筛选归属，避免乐观更新与真实列表漂移
      void refetch();
    };

    const eventListener = (message: MessageEvent<string>) => {
      try {
        const value: unknown = JSON.parse(message.data);
        if (isImportJobEvent(value)) applyEvent(value);
      } catch {
        // 无效的尽力通知忽略，HTTP 仍为权威
      }
    };

    const heartbeatListener = () => {
      setState((current) => ({ ...current, lastEventAt: Date.now() }));
    };

    const connect = () => {
      clearReconnect();
      closeSource();
      if (!mountedRef.current || !enabled || document.hidden || !navigator.onLine) return;
      setState((current) => ({
        ...current,
        mode: failureCountRef.current >= 3 ? 'polling' : 'connecting',
      }));
      const source = new EventSource(importEventsUrl);
      sourceRef.current = source;
      source.addEventListener('import.progress', eventListener as EventListener);
      source.addEventListener('import.succeeded', eventListener as EventListener);
      source.addEventListener('import.partial', eventListener as EventListener);
      source.addEventListener('import.failed', eventListener as EventListener);
      source.addEventListener('heartbeat', heartbeatListener);
      source.onopen = () => {
        if (sourceRef.current !== source) return;
        const wasRecovering = failureCountRef.current > 0;
        failureCountRef.current = 0;
        setState((current) => ({ ...current, mode: 'sse', consecutiveFailures: 0 }));
        // 恢复先校准：重连成功后先 invalidateQueries 再继续
        if (wasRecovering) void refetch();
      };
      source.onerror = () => {
        if (sourceRef.current !== source) return;
        closeSource();
        failureCountRef.current += 1;
        const failures = failureCountRef.current;
        const polling = failures >= 3;
        setState((current) => ({
          ...current,
          mode: navigator.onLine ? (polling ? 'polling' : 'connecting') : 'offline',
          consecutiveFailures: failures,
        }));
        if (!navigator.onLine) return;
        const delay = RECONNECT_DELAYS[Math.min(failures - 1, RECONNECT_DELAYS.length - 1)];
        reconnectTimerRef.current = window.setTimeout(connect, delay);
      };
    };

    const handleOffline = () => {
      clearReconnect();
      closeSource();
      setState((current) => ({ ...current, mode: 'offline' }));
    };
    const handleOnline = () => {
      failureCountRef.current = 0;
      setState({ mode: 'connecting', consecutiveFailures: 0 });
      void refetch();
      connect();
    };
    const handleVisibility = () => {
      if (document.hidden) {
        clearReconnect();
        closeSource();
        setState((current) => ({ ...current, mode: 'polling' }));
      } else {
        void refetch();
        connect();
      }
    };

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    document.addEventListener('visibilitychange', handleVisibility);
    if (enabled) connect();
    else closeSource();

    return () => {
      mountedRef.current = false;
      clearReconnect();
      closeSource();
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
      document.removeEventListener('visibilitychange', handleVisibility);
    };
  }, [enabled, queryClient]);

  return state;
}
