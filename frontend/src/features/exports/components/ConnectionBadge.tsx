import { Badge, Tooltip } from 'antd';
import type { BadgeProps } from 'antd';
import dayjs from 'dayjs';
import type { ExportEventConnectionState } from '../types';

/** 通道状态 → 文案与语义色：sse 实时 / polling 轮询降级 / offline 网络离线 / connecting 连接中。 */
function badgeTheme(mode: ExportEventConnectionState['mode']): {
  status: BadgeProps['status'];
  text: string;
} {
  switch (mode) {
    case 'sse': return { status: 'success', text: '实时推送' };
    case 'polling': return { status: 'warning', text: '轮询中' };
    case 'offline': return { status: 'error', text: '网络离线' };
    default: return { status: 'processing', text: '连接中…' };
  }
}

/**
 * 导出任务 SSE 连接状态徽标。只描述通道状态（连接故障 ≠ 任务失败），
 * Tooltip 展示最近一次事件时间。无自定义色，走 antd 语义色。
 */
export function ConnectionBadge({ connection }: { connection: ExportEventConnectionState }) {
  const theme = badgeTheme(connection.mode);
  const tooltip = connection.lastEventAt !== undefined
    ? `上次事件：${dayjs(connection.lastEventAt).format('HH:mm:ss')}`
    : '等待事件中';
  return (
    <Tooltip title={tooltip}>
      <Badge status={theme.status} text={theme.text} />
    </Tooltip>
  );
}