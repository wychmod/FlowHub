import {
  ExportOutlined,
  ImportOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  OrderedListOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { Button, Layout, Menu, Space, Tag, Typography, theme } from 'antd';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { PAGE_META, pageLabel } from './layoutMeta';
import { HealthBadge } from './HealthBadge';

export type PageKey = 'orders' | 'exports' | 'import-jobs';

const PAGE_ICONS: Record<PageKey, ReactNode> = {
  orders: <OrderedListOutlined />,
  exports: <ExportOutlined />,
  'import-jobs': <ImportOutlined />,
};

// 菜单文案由 PAGE_META 派生，页题与导航共用同一事实源
const MENU_ITEMS = (Object.keys(PAGE_META) as PageKey[]).map((key) => ({
  key,
  icon: PAGE_ICONS[key],
  label: PAGE_META[key].label,
}));

interface AppLayoutProps {
  selectedKey: PageKey;
  onSelect: (key: PageKey) => void;
  children: ReactNode;
}

/** 全局布局壳层（fe-td.md 3）：深色 Sider（品牌区 + 导航）+ 白色顶栏（动态页题），不放业务逻辑。 */
export function AppLayout({ selectedKey, onSelect, children }: AppLayoutProps) {
  const [collapsed, setCollapsed] = useState(false);
  const { token } = theme.useToken();

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider
        width={208}
        collapsible
        collapsed={collapsed}
        trigger={null}
        style={{ position: 'sticky', top: 0, height: '100vh' }}
      >
        {/* 品牌区：与顶栏同高对齐；折叠时收缩为纯图标块 */}
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: collapsed ? 'center' : 'flex-start',
            gap: 10,
            paddingInline: collapsed ? 0 : 20,
          }}
        >
          <div
            style={{
              width: 32,
              height: 32,
              borderRadius: 8,
              background: token.colorPrimary,
              color: token.colorTextLightSolid,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 18,
              flexShrink: 0,
            }}
          >
            <ThunderboltOutlined />
          </div>
          {!collapsed && (
            <span
              style={{
                color: token.colorTextLightSolid,
                fontSize: 16,
                fontWeight: 600,
                letterSpacing: 0.5,
              }}
            >
              FlowHub
            </span>
          )}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => onSelect(key as PageKey)}
          items={MENU_ITEMS}
          style={{ borderInlineEnd: 0 }}
        />
      </Layout.Sider>
      <Layout>
        <Layout.Header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            position: 'sticky',
            top: 0,
            zIndex: 10,
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
          }}
        >
          <Space size={12} align="center">
            <Button
              type="text"
              aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'}
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed((c) => !c)}
            />
            <span
              style={{
                width: 26,
                height: 26,
                borderRadius: 6,
                background: token.colorPrimaryBg,
                color: token.colorPrimary,
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 14,
              }}
            >
              {PAGE_ICONS[selectedKey]}
            </span>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {pageLabel(selectedKey)}
            </Typography.Title>
          </Space>
          <Space size={12} align="center">
            {/* 全局后端健康状态：轮询 /actuator/health，仅描述服务可达性，不承载业务逻辑 */}
            <HealthBadge />
            <Tag color="gold">演示环境</Tag>
          </Space>
        </Layout.Header>
        <Layout.Content style={{ padding: 24 }}>{children}</Layout.Content>
      </Layout>
    </Layout>
  );
}
