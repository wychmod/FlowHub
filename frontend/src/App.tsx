import { useState } from 'react';
import { AppLayout, type PageKey } from './app/AppLayout';
import { ExportJobsPage } from './features/exports/ExportJobsPage';
import { OrderListPage } from './features/orders/OrderListPage';

/**
 * 根组件。当前用轻量的状态切换页面；
 * 引入路由后可替换为 /orders、/exports 两个路由（见 fe-td.md 3）。
 */
export default function App() {
  const [page, setPage] = useState<PageKey>('orders');

  return (
    <AppLayout selectedKey={page} onSelect={setPage}>
      {page === 'orders' ? <OrderListPage /> : <ExportJobsPage />}
    </AppLayout>
  );
}
