import type { ReferencePrompt, VideoRefMode } from '../types';

export const VIDEO_REF_MODE_OPTIONS: { value: VideoRefMode; label: string }[] = [
  { value: 'first_last', label: '首尾帧' },
  { value: 'single', label: '单图' },
  { value: 'none', label: '无参考' },
];

export const VIDEO_REF_MODE_LABELS: Record<VideoRefMode, string> = {
  first_last: '首尾帧',
  single: '单图',
  none: '无参考',
};

/** 规范化模式；storyboard → single；非法/空 → first_last */
export function normalizeVideoRefMode(raw?: string | null): VideoRefMode {
  if (!raw || !raw.trim()) return 'first_last';
  const m = raw.trim().toLowerCase();
  if (m === 'storyboard') return 'single';
  if (m === 'first_last' || m === 'single' || m === 'none') return m;
  return 'first_last';
}

/** 分镜覆盖优先，否则作品默认 */
export function resolveVideoRefMode(
  shotMode?: string | null,
  projectDefault?: string | null,
): VideoRefMode {
  if (shotMode != null && String(shotMode).trim() !== '') {
    return normalizeVideoRefMode(shotMode);
  }
  return normalizeVideoRefMode(projectDefault);
}

/** 按模式筛选要展示的参考图卡（不删数据，仅视图） */
export function visibleRefsForMode(
  refs: ReferencePrompt[] | undefined,
  mode: VideoRefMode,
): ReferencePrompt[] {
  const list = refs ?? [];
  if (mode === 'none') return [];
  if (mode === 'first_last') {
    const first = list.find((r) => r.type === 'first_frame');
    const last = list.find((r) => r.type === 'last_frame');
    const out: ReferencePrompt[] = [];
    if (first) out.push(first);
    if (last) out.push(last);
    // 若缺标准类型，退回前两张以免空白
    if (out.length === 0) return list.slice(0, 2);
    return out;
  }
  // single：优先首帧，否则任意一张
  const main =
    list.find((r) => r.type === 'first_frame') ||
    list.find((r) => r.type === 'other') ||
    list.find((r) => r.type === 'last_frame') ||
    list[0];
  return main ? [main] : [];
}

export function refCardTitle(ref: ReferencePrompt, mode: VideoRefMode): string {
  if (mode === 'single') return '故事版';
  if (ref.type === 'first_frame') return '首帧';
  if (ref.type === 'last_frame') return '尾帧';
  return '参考图';
}
