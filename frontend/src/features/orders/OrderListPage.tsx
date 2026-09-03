import { ExportOutlined, FileExcelOutlined, ReloadOutlined } from '@ant-design/icons';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import {
  Alert,
  App as AntdApp,
  Button,
  Card,
  Empty,
  Form,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType, TableProps } from 'antd';
import dayjs from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import {
  createExportJob,
  type CreateExportJobPayload,
  type ExportJobCreated,
} from '../../api/exportApi';
import { ApiError } from '../../api/http';
import type { PageKey } from '../../app/AppLayout';
import { listOrders, type OrderItem, type OrderPage, type OrderSortField } from './api';
import {
  CURRENCY_OPTIONS,
  DEFAULT_PAGE_SIZE,
  DEFAULT_SORT,
  ORDER_STATUS_OPTIONS,
  PAGE_SIZE_OPTIONS,
  SALES_CHANNEL_OPTIONS,
  STATUS_COLOR,
  enumLabel,
} from './constants';
import { ExportModal, type ExportMode } from './components/ExportModal';
import { OrderFilterForm } from './components/OrderFilterForm';
import {
  formValuesToFilter,
  type OrderFilterFormValues,
  type SubmittedFilter,
  type SubmittedSort,
} from './filters';
import { applySelectionChange } from './selection';

interface OrderListPageProps {
  /** 创建成功后「查看任务」跳转导出任务页（App.tsx 传入 setPage）。 */
  onNavigate: (key: PageKey) => void;
}

/**
 * 订单列表页：页面编排层与唯一状态持有者（docs/order-page-fe/plan.md）。
 * 状态分层：草稿（antd Form 实例）/ 已提交语义（filter/sort/page/pageSize 四个 useState）/
 * 本地 UI（selectedIds、exportModal）；服务端数据只来自 useQuery。
 */
export function OrderListPage({ onNavigate }: OrderListPageProps) {
  const { message, modal } = AntdApp.useApp();
  const [form] = Form.useForm<OrderFilterFormValues>();

  // ==== 已提交查询语义（草稿只存在于 antd Form 实例，点「查询」才复制过来）====
  const [filter, setFilter] = useState<SubmittedFilter>({});
  // null = 默认态（未主动排序，表头不指示）；请求时折算为 DEFAULT_SORT 显式携带（AC6）
  const [sort, setSort] = useState<SubmittedSort | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  // ==== 本地 UI 状态 ====
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [exportModal, setExportModal] = useState<ExportMode | null>(null);

  // 实际生效排序：默认态（null）折算为 DEFAULT_SORT 参与请求与缓存键（两态请求相同可共享缓存）
  const effectiveSort = sort ?? DEFAULT_SORT;

  const { data, error, isError, isFetching, isLoading, isPlaceholderData, refetch } = useQuery<
    OrderPage,
    ApiError
  >({
    queryKey: ['orders', filter, effectiveSort, page, pageSize],
    queryFn: () => listOrders({ ...filter, sort: effectiveSort, page, pageSize }),
    // 换页/排序/筛选期间透出上一份成功数据，表格不闪空（isPlaceholderData 驱动遮罩）
    placeholderData: keepPreviousData,
  });

  // 错误兜底（F18）：placeholder 不作用于 error 态，新 key 失败时 data 回落 undefined，由最近成功数据接住
  const lastDataRef = useRef<OrderPage | undefined>(undefined);
  useEffect(() => {
    if (data !== undefined && !isPlaceholderData) lastDataRef.current = data;
  }, [data, isPlaceholderData]);
  const displayData = data ?? lastDataRef.current;

  // 排序回显对齐（F8）：以响应回显的实际生效排序校正本地 sort（幂等防御，正常情况天然一致）
  useEffect(() => {
    // placeholder 是旧 key 的响应，据此回显会把用户刚点的排序改回去，必须跳过
    if (isPlaceholderData || !data || sort == null) return;
    if (data.sort_by !== sort.field || data.sort_order !== sort.direction) {
      setSort({
        field: data.sort_by as OrderSortField,
        direction: data.sort_order === 'asc' ? 'asc' : 'desc',
      });
    }
  }, [data, isPlaceholderData]);

  // 创建导出任务：幂等键在 mutationFn 内生成，每次明确点击（mutate）均为新 UUID（PRD 7.4.2）
  const createMutation = useMutation<ExportJobCreated, ApiError, CreateExportJobPayload>({
    mutationFn: (payload) => createExportJob(payload, crypto.randomUUID()),
    onSuccess: (job) => {
      setExportModal(null);
      setSelectedIds([]);
      modal.success({
        title: '导出任务已创建',
        content: `任务编号 ${job.job_no}，预计导出 ${job.total_rows} 行。`,
        okText: '查看任务',
        cancelText: '留在本页',
        onOk: () => onNavigate('exports'),
      });
    },
    // onError 不改任何状态：弹窗、勾选与表单内容保留，错误经 mutation.error 传入弹窗渲染（F17）
  });

  /** 排序列受控指示：仅当前排序字段显示方向；默认态（null）三列均不指示。 */
  const orderOf = (field: OrderSortField): 'ascend' | 'descend' | undefined =>
    sort?.field === field ? (sort.direction === 'asc' ? 'ascend' : 'descend') : undefined;

  // 9 列固定 width + tableLayout fixed：换页/排序时列宽不随内容重排，消除水平抖动
  const columns: TableColumnsType<OrderItem> = [
    {
      title: '订单号',
      dataIndex: 'order_no',
      width: 160,
      ellipsis: true,
      sorter: true,
      sortOrder: orderOf('order_no'),
    },
    {
      title: '客户姓名',
      dataIndex: 'customer_name',
      width: 100,
      ellipsis: true,
      render: (value: string | null) => value ?? '—',
    },
    {
      title: '客户手机号',
      dataIndex: 'customer_phone',
      width: 130,
      ellipsis: true,
      render: (value: string | null) => value ?? '—',
    },
    {
      title: '订单状态',
      dataIndex: 'order_status',
      width: 100,
      render: (status: OrderItem['order_status']) => (
        <Tag color={STATUS_COLOR[status]}>{enumLabel(ORDER_STATUS_OPTIONS, status)}</Tag>
      ),
    },
    {
      title: '销售渠道',
      dataIndex: 'sales_channel',
      width: 110,
      ellipsis: true,
      render: (value: OrderItem['sales_channel']) => enumLabel(SALES_CHANNEL_OPTIONS, value),
    },
    {
      title: '订单金额',
      dataIndex: 'total_amount',
      width: 130,
      align: 'right',
      sorter: true,
      sortOrder: orderOf('total_amount'),
      render: (amount: number) => amount.toFixed(2),
    },
    {
      title: '币种',
      dataIndex: 'currency',
      width: 80,
      render: (value: OrderItem['currency']) => enumLabel(CURRENCY_OPTIONS, value),
    },
    {
      title: '收货省份',
      dataIndex: 'shipping_province',
      width: 110,
      ellipsis: true,
      render: (value: string | null) => value ?? '—',
    },
    {
      title: '下单时间',
      dataIndex: 'created_at',
      width: 170,
      sorter: true,
      sortOrder: orderOf('created_at'),
      render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss'),
    },
  ];

  /** 分页与排序统一经 Table.onChange 分派（extra.action 区分），避免与 pagination.onChange 重复触发。 */
  const handleTableChange: TableProps<OrderItem>['onChange'] = (
    pagination,
    _filters,
    sorter,
    extra,
  ) => {
    if (extra.action === 'paginate') {
      const nextPageSize = pagination.pageSize ?? pageSize;
      if (nextPageSize !== pageSize) {
        // 改每页条数回第 1 页；筛选与排序语义原样保留（F4）
        setPageSize(nextPageSize);
        setPage(1);
      } else {
        setPage(pagination.current ?? 1);
      }
    } else if (extra.action === 'sort') {
      const result = Array.isArray(sorter) ? sorter[0] : sorter;
      if (
        result &&
        result.field != null &&
        (result.order === 'ascend' || result.order === 'descend')
      ) {
        setSort({
          field: result.field as OrderSortField,
          direction: result.order === 'ascend' ? 'asc' : 'desc',
        });
      } else {
        // 三态循环的「取消」进入默认态：表头不再指示，请求仍显式携带默认排序（AC6）
        setSort(null);
      }
      setPage(1);
    }
  };

  /** 勾选：onChange 给出跨页累计的完整 key 列表，超 1000 上限时整体拒绝并提示（F10）。 */
  const rowSelection: TableProps<OrderItem>['rowSelection'] = {
    type: 'checkbox',
    selectedRowKeys: selectedIds,
    preserveSelectedRowKeys: true,
    fixed: 'left',
    onChange: (keys) => {
      const next = keys.map((key) => Number(key));
      const { ids, blocked } = applySelectionChange(selectedIds, next);
      if (blocked) {
        message.warning('最多选择 1000 条');
        return;
      }
      setSelectedIds(ids);
    },
  };

  // 页题已上移布局顶栏（AppLayout），页面本体由筛选卡与表格卡两张 Card 组成
  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card>
        <OrderFilterForm
          form={form}
          onSubmit={(values) => {
            // 点查询：草稿复制为已提交条件并回第 1 页（排序不动）
            setFilter(formValuesToFilter(values));
            setPage(1);
          }}
          onReset={() => {
            // 点重置：清草稿、清已提交条件、回第 1 页、清勾选；pageSize 属浏览偏好不动
            form.resetFields();
            setFilter({});
            setPage(1);
            setSelectedIds([]);
          }}
        />
      </Card>
      <Card>
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              minHeight: 32,
            }}
          >
            {selectedIds.length > 0 ? (
              <Space>
                <Typography.Text>已选择 {selectedIds.length} 条</Typography.Text>
                <Button type="link" size="small" onClick={() => setSelectedIds([])}>
                  清空选择
                </Button>
              </Space>
            ) : (
              <span />
            )}
            <Space>
              <Button icon={<ReloadOutlined />} loading={isFetching} onClick={() => void refetch()}>
                刷新
              </Button>
              <Button
                icon={<ExportOutlined />}
                disabled={selectedIds.length === 0}
                onClick={() => setExportModal('SELECTED_IDS')}
              >
                导出已选
              </Button>
              <Button
                icon={<FileExcelOutlined />}
                disabled={displayData?.total === 0}
                onClick={() => setExportModal('FILTER')}
              >
                导出筛选结果
              </Button>
            </Space>
          </div>
          {/* 错误 Alert 保留条件渲染：罕见路径只引起纵向位移不横跳，不值得常驻空槽位 */}
          {isError ? (
            <Alert
              type="error"
              showIcon
              message="订单加载失败"
              description={
                error.traceId ? `${error.message}（trace_id: ${error.traceId}）` : error.message
              }
              action={
                <Button size="small" onClick={() => void refetch()}>
                  重新加载
                </Button>
              }
            />
          ) : null}
          <Table<OrderItem>
            className="orders-table"
            size="middle"
            rowKey="id"
            columns={columns}
            dataSource={displayData?.items ?? []}
            loading={isLoading || (isPlaceholderData && isFetching)}
            rowSelection={rowSelection}
            onChange={handleTableChange}
            scroll={{ x: 1120, y: 480 }}
            tableLayout="fixed"
            locale={{
              emptyText: (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的订单" />
              ),
            }}
            pagination={{
              current: page,
              pageSize,
              pageSizeOptions: PAGE_SIZE_OPTIONS.map(String),
              total: displayData?.total ?? 0,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条`,
            }}
          />
        </Space>
      </Card>
      {exportModal ? (
        <ExportModal
          open
          mode={exportModal}
          selectedIds={selectedIds}
          filter={filter}
          sort={effectiveSort}
          filterTotal={displayData?.total}
          submitting={createMutation.isPending}
          error={createMutation.error}
          onCancel={() => setExportModal(null)}
          onSubmit={(payload) => createMutation.mutate(payload)}
        />
      ) : null}
    </Space>
  );
}
