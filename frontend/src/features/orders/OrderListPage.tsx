import { ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';
import { listOrders, type OrderItem } from './api';

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
 * 订单列表页（fe-td.md 3 中 features/orders 的最小实现）：
 * 演示 react-query 数据获取 + antd Table 服务端分页 + dayjs 时间格式化。
 */
export function OrderListPage() {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);

  const { data, isFetching, refetch } = useQuery({
    queryKey: ['orders', page, pageSize],
    queryFn: () => listOrders({ page, pageSize }),
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
      <Table<OrderItem>
        rowKey="id"
        columns={COLUMNS}
        dataSource={data?.items ?? []}
        loading={isFetching}
        pagination={{
          current: page,
          pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          showTotal: (total) => `共 ${total} 条`,
          onChange: (nextPage, nextPageSize) => {
            setPage(nextPage);
            setPageSize(nextPageSize);
          },
        }}
      />
    </div>
  );
}
