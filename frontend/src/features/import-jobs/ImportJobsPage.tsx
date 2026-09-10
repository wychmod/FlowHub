import { CheckCircleOutlined, FileExcelOutlined, RedoOutlined, ReloadOutlined } from '@ant-design/icons';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Empty, Popconfirm, Progress, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';
import {
  downloadImportErrorReport,
  listImportJobs,
  retryImportJob,
  type ImportJobAccepted,
  type ImportJobItem,
  type ImportJobPage,
  type ImportJobStatus,
} from '../../api/importApi';
import { ApiError } from '../../api/http';
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '../orders/constants';
import { ConnectionBadge } from '../exports/components/ConnectionBadge';
import { IMPORT_ACTIVE_STATUSES, IMPORT_STATUS_META } from './importConstants';
import { useImportEvents } from './useImportEvents';

/** 错误统一文案：ApiError 带 trace_id，其余取 Error.message 兜底。 */
function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return `${error.message}${error.traceId ? `（trace_id: ${error.traceId}）` : ''}`;
  }
  return error instanceof Error ? error.message : '未知错误';
}

/** 进度条状态：失败为异常红，全部成功/部分成功为完成绿，其余活动态。 */
function progressStatus(status: ImportJobStatus): 'success' | 'exception' | 'active' {
  if (status === 'FAILED') return 'exception';
  if (status === 'SUCCEEDED' || status === 'PARTIAL') return 'success';
  return 'active';
}

/**
 * 导入任务页（docs/import-page-fe/plan.md）：
 * SSE 实时进度（useImportEvents）→ 乐观更新缓存 → 列表展示；PARTIAL 下载错误报告、FAILED 重试。
 * 三条件轮询降级同导出：SSE 非 sse 模式 + 有进行中任务时按可见性 3s/15s，否则关闭。
 */
export function ImportJobsPage() {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [actionError, setActionError] = useState('');
  const [busyJobId, setBusyJobId] = useState<number>();
  const connection = useImportEvents(true);

  const jobs = useQuery<ImportJobPage, ApiError>({
    queryKey: ['importJobs', { page, page_size: pageSize }],
    queryFn: () => listImportJobs({ page, pageSize }),
    placeholderData: keepPreviousData,
    refetchInterval: (query) => {
      const hasActive = query.state.data?.items.some((job) => IMPORT_ACTIVE_STATUSES.includes(job.status));
      if (!hasActive || connection.mode === 'sse') return false;
      return document.hidden ? 15_000 : 3_000;
    },
  });

  const retry = useMutation<ImportJobAccepted, ApiError, number>({
    mutationFn: (jobId) => retryImportJob(jobId),
    onSuccess: () => setActionError(''),
  });

  async function handleRetry(job: ImportJobItem) {
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

  async function handleErrorReport(job: ImportJobItem) {
    setActionError('');
    setBusyJobId(job.job_id);
    try {
      await downloadImportErrorReport(job);
    } catch (error) {
      setActionError(errorMessage(error));
    } finally {
      setBusyJobId(undefined);
    }
  }

  const columns: TableColumnsType<ImportJobItem> = [
    {
      title: '任务编号',
      dataIndex: 'job_no',
      width: 190,
      ellipsis: true,
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '文件名',
      dataIndex: 'file_name',
      width: 180,
      ellipsis: true,
      render: (value: string | undefined) => value ?? '—',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (status: ImportJobStatus, job) => (
        <Space size={4}>
          <Tag
            color={IMPORT_STATUS_META[status].color}
            icon={status === 'SUCCEEDED' ? <CheckCircleOutlined /> : undefined}
          >
            {IMPORT_STATUS_META[status].label}
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
            status={progressStatus(job.status)}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
            {job.processed_rows}/{job.total_rows}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '结果统计',
      key: 'result',
      width: 200,
      render: (_: unknown, job) => {
        const summary = job.error_summary ?? [];
        return (
          <Space size={8}>
            <Typography.Text style={{ fontSize: 12 }}>
              成功 <Typography.Text type="success" strong>{job.succeeded_rows}</Typography.Text>
              {' / 跳过 '}
              <Typography.Text type={job.skipped_rows > 0 ? 'warning' : 'secondary'} strong>
                {job.skipped_rows}
              </Typography.Text>
            </Typography.Text>
            {job.status === 'PARTIAL' && summary.length > 0 && (
              <Tooltip
                title={
                  <div>
                    {summary.map((item) => (
                      <div key={item.reason}>{`${item.reason} ×${item.count}`}</div>
                    ))}
                  </div>
                }
              >
                <Typography.Text type="secondary" style={{ cursor: 'help', fontSize: 12 }}>
                  错误分类
                </Typography.Text>
              </Tooltip>
            )}
          </Space>
        );
      },
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
      render: (value: string | null | undefined) =>
        (value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 150,
      render: (_: unknown, job) => (
        <Space size={0}>
          {job.status === 'PARTIAL' && job.error_report_available && (
            <Button
              type="link"
              size="small"
              icon={<FileExcelOutlined />}
              loading={busyJobId === job.job_id}
              aria-label={`下载错误报告 ${job.job_no}`}
              onClick={() => void handleErrorReport(job)}
            >
              错误报告
            </Button>
          )}
          {job.status === 'FAILED' && (
            <Popconfirm
              title="重试该导入任务？"
              description="将重新执行导入，失败历史保留"
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
        <Table<ImportJobItem>
          size="small"
          rowKey="job_id"
          columns={columns}
          dataSource={jobs.data?.items ?? []}
          loading={jobs.isLoading || (jobs.isPlaceholderData && jobs.isFetching)}
          scroll={{ x: 1380, y: 480 }}
          tableLayout="fixed"
          locale={{
            emptyText: (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无导入任务，去订单列表导入订单" />
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
