import { ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';
import { ApiError } from '../../api/http';
import { listOrders, type OrderItem, type OrderPage, type OrderQuery } from './api';

const PAGE_SIZE_OPTIONS = [10, 20, 30, 50] as const;

const DEFAULT_QUERY: Required<OrderQuery> = {
  page: 1,
  pageSize: 10,
};

const STATUS_COLOR: Record<string, string> = {
  PENDING: 'orange',
  PAID: 'blue',
  SHIPPED: 'cyan',
  COMPLETED: 'green',
  CANCELED: 'red',
};

const COLUMNS: TableColumnsType<OrderItem> = [
  { title: '订单号', dataIndex: 'order_no' },
  {
    title: '订单状态',
    dataIndex: 'order_status',
    render: (status: string) => <Tag color={STATUS_COLOR[status] ?? 'default'}>{status}</Tag>,
  },
  { title: '销售渠道', dataIndex: 'sales_channel' },
  { title: '客户姓名', dataIndex: 'customer_name' },
  {
    title: '订单金额',
    dataIndex: 'total_amount',
    align: 'right',
    render: (amount: number, record) => `${amount.toFixed(2)} ${record.currency}`,
  },
  {
    title: '下单时间',
    dataIndex: 'created_at',
    render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss'),
  },
];

/**
 * 订单列表页。
 * 页面只维护当前后端已实现的查询条件：页码 page 与每页条数 page_size。
 */
export function OrderListPage() {
  const [query, setQuery] = useState<Required<OrderQuery>>(DEFAULT_QUERY);

  const { data, error, isError, isFetching, refetch } = useQuery<OrderPage, ApiError>({
    queryKey: ['orders', query],
    queryFn: () => listOrders(query),
  });

  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
        }}
      >
        <Typography.Title level={5} style={{ margin: 0 }}>
          订单列表
        </Typography.Title>
        <Button icon={<ReloadOutlined />} loading={isFetching} onClick={() => void refetch()}>
          刷新
        </Button>
      </div>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {isError ? (
          <Alert
            type="error"
            showIcon
            message="订单加载失败"
            description={error.traceId ? `${error.message}（trace_id: ${error.traceId}）` : error.message}
          />
        ) : null}
        <Table<OrderItem>
          rowKey="id"
          columns={COLUMNS}
          dataSource={data?.items ?? []}
          loading={isFetching}
          pagination={{
            current: query.page,
            pageSize: query.pageSize,
            pageSizeOptions: PAGE_SIZE_OPTIONS.map(String),
            total: data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (nextPage, nextPageSize) => {
              setQuery((prev) => ({
                page: nextPageSize === prev.pageSize ? nextPage : 1,
                pageSize: nextPageSize,
              }));
            },
          }}
        />
      </Space>
    </div>
  );
}
