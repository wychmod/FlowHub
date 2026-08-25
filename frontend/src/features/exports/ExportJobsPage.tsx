import { useQuery } from '@tanstack/react-query';
import { Alert, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import dayjs from 'dayjs';
import { listExportJobs, type ExportJobItem, type ExportJobStatus } from '../../api/exportApi';

const STATUS_COLOR: Record<ExportJobStatus, string> = {
  PENDING: 'orange',
  RUNNING: 'processing',
  SUCCEEDED: 'green',
  FAILED: 'red',
  EXPIRED: 'default',
};

const COLUMNS: TableColumnsType<ExportJobItem> = [
  { title: '任务 ID', dataIndex: 'job_id' },
  { title: '任务编号', dataIndex: 'job_no' },
  {
    title: '状态',
    dataIndex: 'status',
    render: (status: ExportJobStatus) => <Tag color={STATUS_COLOR[status]}>{status}</Tag>,
  },
  {
    title: '创建时间',
    dataIndex: 'created_at',
    render: (value: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'),
  },
];

/**
 * 导出任务页（fe-td.md 3 中 features/exports 的最小实现）：
 * 接口与页面链路已就位，任务创建、SSE 进度、下载、重试在后续迭代实现。
 */
export function ExportJobsPage() {
  const { data, isFetching } = useQuery({
    queryKey: ['export-jobs'],
    queryFn: () => listExportJobs({ page: 1, pageSize: 10 }),
  });

  return (
    <div>
      <Typography.Title level={5} style={{ margin: 0, marginBottom: 16 }}>
        导出任务
      </Typography.Title>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="导出任务为占位示例"
        description="GET /api/v1/export-jobs 已就绪并返回空列表；任务创建、进度推送（SSE）、下载与重试将在后续迭代按 be-td.md 实现。"
      />
      <Table<ExportJobItem>
        rowKey="job_id"
        columns={COLUMNS}
        dataSource={data?.items ?? []}
        loading={isFetching}
        pagination={false}
      />
    </div>
  );
}
