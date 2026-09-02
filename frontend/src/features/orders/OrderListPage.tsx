import { ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';
import { ApiError } from '../../api/http';
import { listOrders, type OrderItem, type OrderPage } from './api';

const PAGE_SIZE_OPTIONS = [10, 20, 30, 50] as const;

/** 页面当前使用的查询状态：筛选/排序等完整契约见 api.ts 的 OrderQuery（设计文档第八节第 7 步）。 */
interface PageState {
  page: number;
  pageSize: number;
}

const DEFAULT_QUERY: PageState = {
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
  { title: '收货省份', dataIndex: 'shipping_province' },
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
 * 页面当前仅维护分页状态（page / page_size）；筛选与排序参数的完整契约已在 api.ts 的
 * OrderQuery 中就绪（docs/order-query-design.md 第八节第 7 步），后续接入筛选表单时直接扩展。
 */
export function OrderListPage() {
  const [query, setQuery] = useState<PageState>(DEFAULT_QUERY);

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
