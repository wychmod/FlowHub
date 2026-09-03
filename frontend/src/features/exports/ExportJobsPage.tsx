import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Empty, Table, Tag } from 'antd';
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

// 固定列宽 + tableLayout fixed，与订单列表页观感与防抖动口径一致
const COLUMNS: TableColumnsType<ExportJobItem> = [
  { title: '任务 ID', dataIndex: 'job_id', width: 280, ellipsis: true },
  { title: '任务编号', dataIndex: 'job_no', width: 180, ellipsis: true },
  {
    title: '状态',
    dataIndex: 'status',
    width: 100,
    render: (status: ExportJobStatus) => <Tag color={STATUS_COLOR[status]}>{status}</Tag>,
  },
  {
    title: '创建时间',
    dataIndex: 'created_at',
    width: 170,
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
    <Card>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="导出任务为占位示例"
        description="GET /api/v1/export-jobs 已就绪并返回空列表；任务创建、进度推送（SSE）、下载与重试将在后续迭代按 be-td.md 实现。"
      />
      <Table<ExportJobItem>
        size="middle"
        rowKey="job_id"
        columns={COLUMNS}
        dataSource={data?.items ?? []}
        loading={isFetching}
        scroll={{ x: 730 }}
        tableLayout="fixed"
        locale={{
          emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无导出任务" />,
        }}
        pagination={false}
      />
    </Card>
  );
}
