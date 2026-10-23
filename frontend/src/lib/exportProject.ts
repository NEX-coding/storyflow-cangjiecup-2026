import type { Asset, ReferencePrompt, ScriptEntry, Shot } from '../types';

export interface ExportProjectPayload {
  project: {
    title: string;
    style: string;
    exportedAt: string;
  };
  source: string;
  scripts: ScriptEntry[];
  assets: Asset[];
  shots: Shot[];
}

const REF_TYPE_LABELS: Record<string, string> = {
  first_frame: '首帧',
  last_frame: '尾帧',
  other: '其他',
};

const ASSET_TYPE_LABELS: Record<string, string> = {
  character: '人物',
  scene: '场景',
  prop: '道具',
};

export async function copyText(text: string): Promise<void> {
  const value = text ?? '';
  if (!value.trim()) {
    throw new Error('内容为空');
  }
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }
  const area = document.createElement('textarea');
  area.value = value;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.left = '-9999px';
  document.body.appendChild(area);
  area.select();
  document.execCommand('copy');
  document.body.removeChild(area);
}

export function downloadTextFile(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

function sanitizeFilename(name: string): string {
  return (name || 'storyflow')
    .replace(/[\\/:*?"<>|]+/g, '_')
    .replace(/\s+/g, '_')
    .slice(0, 80);
}

function scriptBody(script?: ScriptEntry | null): string {
  if (!script) return '';
  const parts: string[] = [];
  const characters = (script.characters || []).filter(Boolean).join('、');
  if (characters) parts.push(characters);
  if (script.action?.trim()) parts.push(script.action.trim());
  if (script.dialogue?.trim()) parts.push(script.dialogue.trim());
  if (script.shot?.trim()) parts.push(script.shot.trim());
  return parts.join('\n\n');
}

function formatRef(ref: ReferencePrompt): string {
  const label = REF_TYPE_LABELS[ref.type] || ref.type;
  const note = ref.note?.trim() ? `\n> ${ref.note.trim()}` : '';
  return `**${label}**\n\n\`\`\`\n${ref.prompt || ''}\n\`\`\`${note}`;
}

export function buildExportMarkdown(data: ExportProjectPayload): string {
  const scriptById = new Map((data.scripts || []).map((s) => [s.id, s]));
  const assetById = new Map((data.assets || []).map((a) => [a.id, a]));
  const shots = [...(data.shots || [])].sort((a, b) => a.orderIndex - b.orderIndex);

  const lines: string[] = [];
  lines.push(`# ${data.project.title || 'StoryFlow 项目'}`);
  lines.push('');
  lines.push(`- 风格：${data.project.style || '—'}`);
  lines.push(`- 导出时间：${data.project.exportedAt || '—'}`);
  lines.push(`- 分镜数：${shots.length}`);
  lines.push(`- 资产数：${(data.assets || []).length}`);
  lines.push('');

  if (data.assets?.length) {
    lines.push('## 资产库');
    lines.push('');
    for (const asset of data.assets) {
      lines.push(`### ${ASSET_TYPE_LABELS[asset.type] || asset.type} · ${asset.name}`);
      if (asset.description?.trim()) {
        lines.push('');
        lines.push(asset.description.trim());
      }
      if (asset.referenceImageUrl) {
        lines.push('');
        lines.push(`**参考图**：${asset.referenceImageUrl}`);
      }
      if (asset.imagePrompt?.trim()) {
        lines.push('');
        lines.push('**生图提示词**');
        lines.push('');
        lines.push('```');
        lines.push(asset.imagePrompt.trim());
        lines.push('```');
      }
      lines.push('');
    }
  }

  lines.push('## 分镜提示词包');
  lines.push('');

  if (shots.length === 0) {
    lines.push('_暂无分镜_');
    lines.push('');
  }

  shots.forEach((shot, index) => {
    const script = shot.scriptId != null ? scriptById.get(shot.scriptId) : undefined;
    lines.push(`### 分镜 #${index + 1}`);
    lines.push('');

    const body = scriptBody(script);
    if (body) {
      lines.push('**剧本**');
      lines.push('');
      lines.push(body);
      lines.push('');
    }

    lines.push('**视频提示词**');
    lines.push('');
    lines.push('```');
    lines.push(shot.videoPrompt?.trim() || '');
    lines.push('```');
    lines.push('');

    if (shot.referencePrompts?.length) {
      lines.push('**参考图提示词**');
      lines.push('');
      shot.referencePrompts.forEach((ref) => {
        lines.push(formatRef(ref));
        lines.push('');
      });
    }

    const linked = (shot.linkedAssetIds || [])
      .map((id) => assetById.get(id))
      .filter(Boolean) as Asset[];
    if (linked.length) {
      lines.push('**关联资产**');
      lines.push('');
      lines.push(linked.map((a) => `- ${ASSET_TYPE_LABELS[a.type] || a.type}：${a.name}`).join('\n'));
      lines.push('');
    }
  });

  return `${lines.join('\n').trim()}\n`;
}

export function exportFilename(title: string, format: 'json' | 'markdown'): string {
  const base = sanitizeFilename(title);
  return format === 'markdown' ? `${base}_提示词包.md` : `${base}_提示词包.json`;
}

/** 仅保留选中分镜及其关联剧本/资产，用于批量导出选中项 */
export function filterExportByShotIds(
  data: ExportProjectPayload,
  shotIds: number[],
): ExportProjectPayload {
  const idSet = new Set(shotIds);
  const shots = (data.shots || [])
    .filter((s) => idSet.has(s.id))
    .sort((a, b) => a.orderIndex - b.orderIndex);
  const scriptIds = new Set(shots.map((s) => s.scriptId).filter((id): id is number => id != null));
  const assetIds = new Set(shots.flatMap((s) => s.linkedAssetIds || []));
  return {
    ...data,
    project: {
      ...data.project,
      exportedAt: new Date().toISOString(),
    },
    scripts: (data.scripts || []).filter((s) => scriptIds.has(s.id)),
    assets: (data.assets || []).filter((a) => assetIds.has(a.id)),
    shots,
  };
}
