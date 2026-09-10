import { CheckCircleOutlined, DownloadOutlined, RedoOutlined, ReloadOutlined } from '@ant-design/icons';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Empty, Popconfirm, Progress, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';
import {
  downloadExportJob,
  listExportJobs,
  retryExportJob,
  type ExportJobCreated,
  type ExportJobItem,
  type ExportJobPage,
  type ExportJobStatus,
} from '../../api/exportApi';
import { ApiError } from '../../api/http';
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '../orders/constants';
import { ConnectionBadge } from './components/ConnectionBadge';
import { useExportEvents } from './useExportEvents';

const STATUS_META: Record<ExportJobStatus, { label: string; color: string }> = {
  PENDING: { label: '排队中', color: 'orange' },
  RUNNING: { label: '执行中', color: 'processing' },
  SUCCEEDED: { label: '已完成', color: 'green' },
  FAILED: { label: '失败', color: 'red' },
  EXPIRED: { label: '已过期', color: 'default' },
};

/** 错误统一文案：ApiError 带 trace_id，其余取 Error.message 兜底。 */
function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return `${error.message}${error.traceId ? `（trace_id: ${error.traceId}）` : ''}`;
  }
  return error instanceof Error ? error.message : '未知错误';
}

/** 字节数人类可读：B / KB / MB。 */
function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * 导出任务页（docs/export-sse-design.md 交付）：
 * SSE 实时进度（useExportEvents）→ 乐观更新缓存 → 列表展示；下载/重试操作列。
 * 三条件轮询：SSE 非 sse 模式 + 有进行中任务时按页面可见性 3s/15s，否则关闭。
 */
export function ExportJobsPage() {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [actionError, setActionError] = useState('');
  const [busyJobId, setBusyJobId] = useState<number>();
  const connection = useExportEvents(true);

  const jobs = useQuery<ExportJobPage, ApiError>({
    queryKey: ['exportJobs', { page, page_size: pageSize }],
    queryFn: () => listExportJobs({ page, pageSize }),
    // 换页期间透出上一份成功数据，表格不闪空
    placeholderData: keepPreviousData,
    // 三条件轮询（降级）：非 sse 模式 + 有 PENDING/RUNNING 才轮询，否则关闭
    refetchInterval: (query) => {
      const hasActive = query.state.data?.items.some(
        (job) => job.status === 'PENDING' || job.status === 'RUNNING',
      );
      if (!hasActive || connection.mode === 'sse') return false;
      return document.hidden ? 15_000 : 3_000;
    },
  });

  const retry = useMutation<ExportJobCreated, ApiError, number>({
    mutationFn: (jobId) => retryExportJob(jobId),
    onSuccess: () => {
      // 重试受理后回 PENDING：交由 SSE/校准收敛，这里只需失效重取
      setActionError('');
    },
  });

  async function handleRetry(job: ExportJobItem) {
    setActionError('');
    setBusyJobId(job.job_id);
    try {
      await retry.mutateAsync(job.job_id);
      await jobs.refetch();
    } catch (error) {
      setActionError(errorMessage(error));
    } finally {
      setBusyJobId(undefined);
    }
  }

  async function handleDownload(job: ExportJobItem) {
    setActionError('');
    setBusyJobId(job.job_id);
    try {
      await downloadExportJob(job);
    } catch (error) {
      setActionError(errorMessage(error));
    } finally {
      setBusyJobId(undefined);
    }
  }

  const columns: TableColumnsType<ExportJobItem> = [
    {
      title: '任务编号',
      dataIndex: 'job_no',
      width: 200,
      ellipsis: true,
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '文件名 / 范围',
      key: 'range',
      width: 220,
      ellipsis: true,
      render: (_: unknown, job) => job.file_name ?? '—',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (status: ExportJobStatus, job) => (
        <Space size={4}>
          <Tag color={STATUS_META[status].color} icon={status === 'SUCCEEDED' ? <CheckCircleOutlined /> : undefined}>
            {STATUS_META[status].label}
          </Tag>
          {status === 'FAILED' && job.error_message && (
            <Tooltip title={`${job.error_code ?? 'UNKNOWN'}：${job.error_message}`}>
              <Typography.Text type="danger" style={{ cursor: 'help' }}>原因</Typography.Text>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '进度',
      dataIndex: 'progress_percent',
      width: 200,
      render: (_: number, job) => (
        <Space size={8} style={{ width: '100%' }}>
          <Progress
            percent={job.progress_percent}
            size="small"
            style={{ minWidth: 90, margin: 0 }}
            status={job.status === 'FAILED' ? 'exception' : job.status === 'SUCCEEDED' ? 'success' : 'active'}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
            {job.processed_rows}/{job.total_rows}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '文件大小',
      dataIndex: 'file_size_bytes',
      width: 100,
      align: 'right',
      render: (bytes: number | null | undefined) => formatBytes(bytes),
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      width: 170,
      render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '完成时间',
      dataIndex: 'finished_at',
      width: 170,
      render: (value: string | null | undefined) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      render: (_: unknown, job) => (
        <Space size={0}>
          {job.downloadable && (
            <Button
              type="link"
              size="small"
              icon={<DownloadOutlined />}
              loading={busyJobId === job.job_id}
              aria-label={`下载 ${job.job_no}`}
              onClick={() => void handleDownload(job)}
            >
              下载
            </Button>
          )}
          {job.status === 'FAILED' && (
            <Popconfirm
              title="重试该导出任务？"
              description="将重新生成导出文件，失败历史保留"
              okText="重试"
              cancelText="取消"
              onConfirm={() => void handleRetry(job)}
            >
              <Button
                type="link"
                size="small"
                icon={<RedoOutlined />}
                loading={busyJobId === job.job_id}
                aria-label={`重试 ${job.job_no}`}
              >
                重试
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Space size={16}>
            <ConnectionBadge connection={connection} />
            <Typography.Text type="secondary">共 {jobs.data?.total ?? 0} 条任务</Typography.Text>
          </Space>
          <Button icon={<ReloadOutlined />} loading={jobs.isFetching} onClick={() => void jobs.refetch()}>
            刷新
          </Button>
        </div>
      </Card>
      <Card>
        {actionError ? (
          <Alert type="error" showIcon style={{ marginBottom: 16 }} title={actionError} role="alert" />
        ) : null}
        {jobs.error ? (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
            title={errorMessage(jobs.error)}
            action={<Button size="small" onClick={() => void jobs.refetch()}>重新加载</Button>}
          />
        ) : null}
        <Table<ExportJobItem>
          size="small"
          rowKey="job_id"
          columns={columns}
          dataSource={jobs.data?.items ?? []}
          loading={jobs.isLoading || (jobs.isPlaceholderData && jobs.isFetching)}
          scroll={{ x: 1300, y: 480 }}
          tableLayout="fixed"
          locale={{
            emptyText: (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无导出任务，去订单管理创建" />
            ),
          }}
          pagination={{
            current: page,
            pageSize,
            pageSizeOptions: PAGE_SIZE_OPTIONS.map(String),
            total: jobs.data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条任务`,
            onChange: (nextPage, nextPageSize) => {
              if (nextPageSize !== pageSize) {
                setPageSize(nextPageSize);
                setPage(1);
              } else {
                setPage(nextPage);
              }
            },
          }}
        />
      </Card>
    </Space>
  );
}
