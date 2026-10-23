import type { AspectRatio } from '../types';

export const VIDEO_ASPECT_RATIOS: AspectRatio[] = ['16:9', '9:16', '1:1', '4:3', '3:4'];
export const IMAGE_ASPECT_RATIOS: AspectRatio[] = ['1:1', '16:9', '9:16', '4:3', '3:4'];

export const ASPECT_RATIO_LABELS: Record<AspectRatio, string> = {
  '16:9': '16:9 横屏',
  '9:16': '9:16 竖屏',
  '1:1': '1:1 方形',
  '4:3': '4:3',
  '3:4': '3:4',
};

export function normalizeVideoAspectRatio(raw?: string | null): AspectRatio {
  const v = String(raw || '')
    .trim()
    .replace('：', ':')
    .replace(/[x×]/gi, ':');
  if ((VIDEO_ASPECT_RATIOS as string[]).includes(v)) return v as AspectRatio;
  return '16:9';
}

export function normalizeImageAspectRatio(raw?: string | null): AspectRatio {
  const v = String(raw || '')
    .trim()
    .replace('：', ':')
    .replace(/[x×]/gi, ':');
  if ((IMAGE_ASPECT_RATIOS as string[]).includes(v)) return v as AspectRatio;
  return '1:1';
}
