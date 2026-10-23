import type { FeedbackStatus, FeedbackType } from '../types';

export const TYPE_OPTIONS: { value: FeedbackType; label: string; color: string }[] = [
  { value: 'bug', label: '问题反馈', color: 'error' },
  { value: 'feature', label: '功能建议', color: 'processing' },
  { value: 'improvement', label: '体验优化', color: 'success' },
  { value: 'other', label: '其他', color: 'default' },
];

export const STATUS_OPTIONS: { value: FeedbackStatus; label: string; color: string }[] = [
  { value: 'pending', label: '待处理', color: 'warning' },
  { value: 'processing', label: '处理中', color: 'processing' },
  { value: 'resolved', label: '已解决', color: 'success' },
  { value: 'closed', label: '已关闭', color: 'default' },
];

export function typeMeta(type: FeedbackType) {
  return TYPE_OPTIONS.find((item) => item.value === type) ?? TYPE_OPTIONS[3];
}

export function statusMeta(status?: FeedbackStatus | string) {
  return STATUS_OPTIONS.find((item) => item.value === status) ?? STATUS_OPTIONS[0];
}

export function formatDate(iso: string) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
