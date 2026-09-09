import { useQuery } from '@tanstack/react-query';
import { Badge, Tooltip } from 'antd';
import type { BadgeProps } from 'antd';
import { fetchHealth, type HealthReport } from '../api/healthApi';

/** 健康报告 → 徽标语义：错误优先（最近一次请求失败即不可达），无数据为检测中，其余按 status。 */
export function healthBadgeTheme(
  report: HealthReport | undefined,
  isError: boolean,
): { status: BadgeProps['status']; text: string } {
  if (isError) return { status: 'error', text: '服务不可达' };
  if (report === undefined) return { status: 'processing', text: '检测中…' };
  return report.status === 'UP'
    ? { status: 'success', text: '服务正常' }
    : { status: 'error', text: '服务异常' };
}

/** 组件状态明细文案（show-details 未开放时仅整体状态可见）。 */
export function healthComponentsText(report: HealthReport | undefined): string {
  if (!report?.components) return '组件详情未开放，仅整体状态可见';
  return Object.entries(report.components)
    .map(([name, component]) => `${name}: ${component.status}`)
    .join('，');
}

/** 顶栏后端健康徽标：轮询 /actuator/health（15s），Tooltip 展示 db/rabbit/redis 等组件细分。 */
export function HealthBadge() {
  const { data, isError } = useQuery({
    queryKey: ['health'],
    queryFn: fetchHealth,
    refetchInterval: 15000,
    retry: 1,
    staleTime: 10000,
  });
  const theme = healthBadgeTheme(data, isError);
  return (
    <Tooltip title={isError ? '无法连接后端健康检查' : healthComponentsText(data)}>
      <Badge status={theme.status} text={theme.text} />
    </Tooltip>
  );
}
