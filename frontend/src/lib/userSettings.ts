export const STYLE_PRESETS = [
  { value: '日漫赛璐璐', label: '日漫赛璐璐', description: '清新二次元，硬边二分影' },
  { value: '国漫厚涂', label: '国漫厚涂', description: '国漫原画，体积笔触' },
  { value: '韩漫清新', label: '韩漫清新', description: '淡彩通透，竖屏恋爱感' },
  { value: '真人写实', label: '真人写实', description: '写真呼吸感，拒绝虚拟人' },
  { value: '3D CG动画', label: '3D CG动画', description: 'UE5次世代 / 三维漫剧' },
  { value: '古风水墨', label: '古风水墨', description: '写意留白，诗意东方' },
  { value: '赛博朋克', label: '赛博朋克', description: '霓虹雨夜，机能科幻' },
  { value: '电影胶片', label: '电影胶片', description: '胶片颗粒，治愈或冷调' },
] as const;

/** @deprecated legacy preset, mapped to 日漫赛璐璐 */
const LEGACY_STYLE_MAP: Record<string, string> = {
  日系动画: '日漫赛璐璐',
  写实: '真人写实',
};

export function normalizeStylePreset(value: string): string {
  return LEGACY_STYLE_MAP[value] ?? value;
}

export function getStyleLabel(value: string): string {
  const normalized = normalizeStylePreset(value);
  return STYLE_PRESETS.find((p) => p.value === normalized)?.label ?? value;
}

export type ExportFormat = 'json' | 'markdown';

export interface UserSettings {
  defaultStylePreset: string;
  defaultExportFormat: ExportFormat;
  assistantAutoOpen: boolean;
}

const STORAGE_KEY = 'storyflow-user-settings';

export const DEFAULT_USER_SETTINGS: UserSettings = {
  defaultStylePreset: '日漫赛璐璐',
  defaultExportFormat: 'json',
  assistantAutoOpen: false,
};

export function loadUserSettings(): UserSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { ...DEFAULT_USER_SETTINGS };
    const parsed = JSON.parse(raw) as Partial<UserSettings>;
    const defaultStylePreset =
      typeof parsed.defaultStylePreset === 'string'
        ? normalizeStylePreset(parsed.defaultStylePreset)
        : DEFAULT_USER_SETTINGS.defaultStylePreset;
    return {
      defaultStylePreset,
      defaultExportFormat:
        parsed.defaultExportFormat === 'markdown' || parsed.defaultExportFormat === 'json'
          ? parsed.defaultExportFormat
          : DEFAULT_USER_SETTINGS.defaultExportFormat,
      assistantAutoOpen:
        typeof parsed.assistantAutoOpen === 'boolean'
          ? parsed.assistantAutoOpen
          : DEFAULT_USER_SETTINGS.assistantAutoOpen,
    };
  } catch {
    return { ...DEFAULT_USER_SETTINGS };
  }
}

export function saveUserSettings(settings: UserSettings) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
}
