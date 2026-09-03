import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp, ConfigProvider } from 'antd';
import type { ThemeConfig } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import React from 'react';
import ReactDOM from 'react-dom/client';
import './styles/index.css';
import App from './App';

dayjs.locale('zh-cn');

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

// 全局主题：主色维持 antd 默认蓝 #1677ff，仅统一圆角/灰底/字体并定制布局壳层组件观感
const themeConfig: ThemeConfig = {
  token: {
    borderRadius: 8,
    colorBgLayout: '#f5f5f5', // 与 index.css body 底色一致（唯一灰底事实源）
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Arial, sans-serif",
  },
  components: {
    Layout: {
      headerBg: '#ffffff', // 白色细顶栏
      headerHeight: 56,
      headerPadding: '0 20px',
      bodyBg: '#f5f5f5',
      siderBg: '#001529', // 深色侧边栏
      triggerBg: '#002140',
    },
    Menu: {
      darkItemBg: 'transparent', // 透出 Sider 底色，整条侧栏同色
      darkItemHoverBg: 'rgba(255, 255, 255, 0.08)',
      itemMarginInline: 10, // 胶囊选中态留边
      itemBorderRadius: 6,
    },
    Table: {
      headerBg: '#fafafa',
      headerSplitColor: 'transparent', // 去表头默认分割线，现代观感
    },
  },
};

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={zhCN} theme={themeConfig}>
        {/* antd App 包装：页面经 App.useApp() 使用 message/modal，静态方法无上下文 */}
        <AntdApp>
          <App />
        </AntdApp>
      </ConfigProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
