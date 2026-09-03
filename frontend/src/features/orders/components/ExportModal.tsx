import { Alert, Button, Checkbox, Form, Input, Modal, Space, Typography } from 'antd';
import { useEffect } from 'react';
import {
  EXPORT_COLUMN_OPTIONS,
  type CreateExportJobPayload,
  type ExportColumnKey,
} from '../../../api/exportApi';
import type { ApiError } from '../../../api/http';
import { buildFilterSnapshot, type SubmittedFilter, type SubmittedSort } from '../filters';

/** 导出模式（be-td.md 4.5）：勾选导出 / 筛选导出。 */
export type ExportMode = 'SELECTED_IDS' | 'FILTER';

interface ExportModalProps {
  open: boolean;
  mode: ExportMode;
  /** SELECTED_IDS 模式：范围数量与 payload 数据源。 */
  selectedIds: readonly number[];
  /** FILTER 模式：快照数据源（取已提交条件，与草稿无关）。 */
  filter: SubmittedFilter;
  sort: SubmittedSort;
  /** FILTER 模式：范围行数展示（当前列表 total），无值显示 —。 */
  filterTotal?: number;
  submitting: boolean;
  /** 创建失败信息（弹窗内 Alert 展示，含 trace_id）；内容与勾选在失败后保留。 */
  error: ApiError | null;
  onCancel: () => void;
  onSubmit: (payload: CreateExportJobPayload) => void;
}

/** 弹窗表单值：columns 为已选导出列，fileName 可选。 */
interface ExportFormValues {
  columns: ExportColumnKey[];
  fileName?: string;
}

/** 默认勾选列（PRD 7.3.2，共 6 列）。 */
const DEFAULT_COLUMNS: ExportColumnKey[] = EXPORT_COLUMN_OPTIONS.filter(
  (option) => option.defaultSelected,
).map((option) => option.key);

/** 全部白名单列，顺序即输出顺序。 */
const ALL_COLUMNS: ExportColumnKey[] = EXPORT_COLUMN_OPTIONS.map((option) => option.key);

/** 导出配置弹窗：范围只读展示 + 导出列/文件名配置 + 提交与失败展示。 */
export function ExportModal({
  open,
  mode,
  selectedIds,
  filter,
  sort,
  filterTotal,
  submitting,
  error,
  onCancel,
  onSubmit,
}: ExportModalProps) {
  const [form] = Form.useForm<ExportFormValues>();

  // 打开时恢复默认（默认 6 列、文件名留空），不依赖 Modal 销毁重建语义
  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  const scopeText =
    mode === 'SELECTED_IDS'
      ? `已选订单 ${selectedIds.length} 条`
      : `当前筛选结果 ${filterTotal ?? '—'} 条`;

  /** 快捷设置导出列后立即重校验，清除「至少选择 1 列」的残留错误。 */
  const applyColumns = (columns: ExportColumnKey[]): void => {
    form.setFieldsValue({ columns });
    void form.validateFields(['columns']).catch(() => undefined);
  };

  const handleFinish = (values: ExportFormValues): void => {
    const selected = new Set(values.columns);
    const payload: CreateExportJobPayload = {
      selection:
        mode === 'SELECTED_IDS'
          ? { mode: 'SELECTED_IDS', order_ids: [...selectedIds] }
          : { mode: 'FILTER', filter: buildFilterSnapshot(filter, sort) },
      // 按白名单固定顺序输出已选列（顺序确定可预期，与点击顺序无关）
      columns: ALL_COLUMNS.filter((key) => selected.has(key)),
    };
    const fileName = values.fileName?.trim();
    if (fileName) payload.file_name = fileName;
    onSubmit(payload);
  };

  return (
    <Modal
      title="创建导出任务"
      open={open}
      okText="创建任务"
      cancelText="取消"
      confirmLoading={submitting}
      cancelButtonProps={{ disabled: submitting }}
      mask={{ closable: false }}
      keyboard={false}
      onOk={() => form.submit()}
      onCancel={onCancel}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ columns: DEFAULT_COLUMNS }}
        onFinish={handleFinish}
      >
        {error ? (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
            message="创建导出任务失败"
            description={
              error.traceId ? `${error.message}（trace_id: ${error.traceId}）` : error.message
            }
          />
        ) : null}
        <Form.Item label="导出范围">
          <Typography.Text>{scopeText}</Typography.Text>
        </Form.Item>
        <Form.Item
          name="columns"
          label="导出列"
          rules={[
            {
              validator: (_, value) =>
                value && value.length > 0
                  ? Promise.resolve()
                  : Promise.reject(new Error('至少选择 1 列')),
            },
          ]}
        >
          <Checkbox.Group
            options={EXPORT_COLUMN_OPTIONS.map((option) => ({
              label: option.label,
              value: option.key,
            }))}
          />
        </Form.Item>
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: -16 }}>
          <Space>
            <Button type="link" size="small" onClick={() => applyColumns(ALL_COLUMNS)}>
              全选
            </Button>
            <Button type="link" size="small" onClick={() => applyColumns(DEFAULT_COLUMNS)}>
              恢复默认
            </Button>
          </Space>
        </div>
        <Form.Item name="fileName" label="文件名（可选）">
          <Input
            maxLength={100}
            showCount
            allowClear
            placeholder="留空按时间自动生成，扩展名 .xlsx 由系统追加"
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
