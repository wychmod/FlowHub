import { DownOutlined, UpOutlined } from '@ant-design/icons';
import { Button, Col, DatePicker, Form, Input, InputNumber, Row, Select, Space } from 'antd';
import type { FormInstance } from 'antd';
import dayjs from 'dayjs';
import { useState } from 'react';
import type { ComponentProps } from 'react';
import type { Dayjs } from 'dayjs';
import { CURRENCY_OPTIONS, ORDER_STATUS_OPTIONS, SALES_CHANNEL_OPTIONS } from '../constants';
import type { OrderFilterFormValues } from '../filters';

const { RangePicker } = DatePicker;

interface OrderFilterFormProps {
  /** 页面创建并传入的表单实例（重置需 resetFields，草稿只存在于该实例）。 */
  form: FormInstance<OrderFilterFormValues>;
  onSubmit: (values: OrderFilterFormValues) => void;
  onReset: () => void;
}

/** 手机号契约：11 位数字（空值放行，非空强校验）。 */
const PHONE_PATTERN = /^\d{11}$/;

/** 下单时间快捷预设（今天/昨天/最近 7 天/最近 30 天）。 */
function buildTimePresets(): ComponentProps<typeof RangePicker>['presets'] {
  const now = dayjs();
  return [
    { label: '今天', value: [now.startOf('day'), now.endOf('day')] },
    { label: '昨天', value: [now.subtract(1, 'day').startOf('day'), now.subtract(1, 'day').endOf('day')] },
    { label: '最近 7 天', value: [now.subtract(6, 'day').startOf('day'), now.endOf('day')] },
    { label: '最近 30 天', value: [now.subtract(29, 'day').startOf('day'), now.endOf('day')] },
  ];
}

/**
 * 筛选草稿表单：只承载草稿输入与字段校验，查询/重置触发的状态迁移在页面层完成。
 * 草稿与已提交条件严格分离——输入过程不触发请求（spec 核心状态原则）。
 */
export function OrderFilterForm({ form, onSubmit, onReset }: OrderFilterFormProps) {
  // 次行筛选区收起/展开；antd Form 字段默认 preserve，收起再展开草稿不丢
  const [expanded, setExpanded] = useState(false);

  return (
    <Form form={form} layout="vertical" onFinish={onSubmit}>
      <Row gutter={16}>
        <Col span={6}>
          <Form.Item name="orderNo" label="订单号">
            <Input maxLength={64} allowClear placeholder="订单号（前缀匹配）" />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="orderStatus" label="订单状态">
            <Select
              mode="multiple"
              maxTagCount="responsive"
              allowClear
              placeholder="全部"
              options={ORDER_STATUS_OPTIONS}
            />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item name="salesChannel" label="销售渠道">
            <Select
              mode="multiple"
              maxTagCount="responsive"
              allowClear
              placeholder="全部"
              options={SALES_CHANNEL_OPTIONS}
            />
          </Form.Item>
        </Col>
        <Col span={6}>
          <Form.Item
            name="createdAtRange"
            label="下单时间"
            rules={[
              {
                validator: (_, value) => {
                  const [begin, end] = (value ?? [null, null]) as [Dayjs | null, Dayjs | null];
                  return begin && end && !begin.isBefore(end)
                    ? Promise.reject(new Error('开始时间须早于结束时间'))
                    : Promise.resolve();
                },
              },
            ]}
          >
            <RangePicker showTime presets={buildTimePresets()} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>
      {expanded ? (
        <Row gutter={16}>
          <Col span={6}>
            <Form.Item name="customerName" label="客户姓名">
              <Input maxLength={128} allowClear placeholder="客户姓名（模糊匹配）" />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item
              name="customerPhone"
              label="客户手机号"
              rules={[
                {
                  validator: (_, value) => {
                    const phone = typeof value === 'string' ? value.trim() : '';
                    return phone === '' || PHONE_PATTERN.test(phone)
                      ? Promise.resolve()
                      : Promise.reject(new Error('手机号须为 11 位数字'));
                  },
                },
              ]}
            >
              <Input maxLength={11} allowClear placeholder="11 位数字（精确匹配）" />
            </Form.Item>
          </Col>
          <Col span={6}>
            <Form.Item name="currency" label="币种">
              <Select
                mode="multiple"
                maxTagCount="responsive"
                allowClear
                placeholder="全部"
                options={CURRENCY_OPTIONS}
              />
            </Form.Item>
          </Col>
          <Col span={6}>
            {/* 金额区间：noStyle 内嵌字段的校验错误冒泡到本层展示 */}
            <Form.Item label="订单金额">
              <Space.Compact block>
                <Form.Item name="totalAmountMin" noStyle>
                  <InputNumber min={0} precision={2} placeholder="最小金额" style={{ width: '100%' }} />
                </Form.Item>
                <Input style={{ width: 36, textAlign: 'center' }} placeholder="~" disabled />
                <Form.Item
                  name="totalAmountMax"
                  noStyle
                  dependencies={['totalAmountMin']}
                  rules={[
                    {
                      validator: (_, value) => {
                        const min = form.getFieldValue('totalAmountMin') as number | null | undefined;
                        return value != null && min != null && value < min
                          ? Promise.reject(new Error('金额上界不能小于下界'))
                          : Promise.resolve();
                      },
                    },
                  ]}
                >
                  <InputNumber min={0} precision={2} placeholder="最大金额" style={{ width: '100%' }} />
                </Form.Item>
              </Space.Compact>
            </Form.Item>
          </Col>
        </Row>
      ) : null}
      <Row>
        <Col span={24} style={{ textAlign: 'right' }}>
          <Space>
            <Button type="primary" htmlType="submit">
              查询
            </Button>
            <Button onClick={onReset}>重置</Button>
            <Button type="link" onClick={() => setExpanded((prev) => !prev)}>
              {expanded ? '收起' : '展开'}
              {expanded ? <UpOutlined /> : <DownOutlined />}
            </Button>
          </Space>
        </Col>
      </Row>
    </Form>
  );
}
