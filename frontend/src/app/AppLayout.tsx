import { ExportOutlined, OrderedListOutlined } from '@ant-design/icons';
import { Layout, Menu, Typography } from 'antd';
import type { ReactNode } from 'react';

export type PageKey = 'orders' | 'exports';

const MENU_ITEMS = [
  { key: 'orders', icon: <OrderedListOutlined />, label: '订单列表' },
  { key: 'exports', icon: <ExportOutlined />, label: '导出任务' },
];

interface AppLayoutProps {
  selectedKey: PageKey;
  onSelect: (key: PageKey) => void;
  children: ReactNode;
}

/** 全局布局壳层（fe-td.md 3）：只负责导航与页面框架，不放业务逻辑。 */
export function AppLayout({ selectedKey, onSelect, children }: AppLayoutProps) {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header
        style={{ display: 'flex', alignItems: 'center', background: '#001529' }}
      >
        <Typography.Title level={4} style={{ color: 'rgba(255, 255, 255, 0.95)', margin: 0 }}>
          ExportFlow 导出中心
        </Typography.Title>
      </Layout.Header>
      <Layout>
        <Layout.Sider width={200}>
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[selectedKey]}
            onClick={({ key }) => onSelect(key as PageKey)}
            items={MENU_ITEMS}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Layout.Sider>
        <Layout.Content style={{ padding: 24 }}>{children}</Layout.Content>
      </Layout>
    </Layout>
  );
}
