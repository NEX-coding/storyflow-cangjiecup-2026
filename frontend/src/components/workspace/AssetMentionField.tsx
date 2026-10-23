import { useMemo, type ReactNode } from 'react';
import { Input } from 'antd';
import type { Asset } from '../../types';

const { TextArea } = Input;

const typeLabel: Record<string, string> = {
  character: '人物',
  scene: '场景',
  prop: '道具',
};

function escapeReg(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** 解析提示词里的 @名称 / (@名称) */
export function parseAssetMentions(text: string, assets: Asset[]): Asset[] {
  if (!text?.trim() || !assets?.length) return [];
  const found: Asset[] = [];
  const seen = new Set<number>();
  const sorted = [...assets].sort((a, b) => b.name.length - a.name.length);
  for (const asset of sorted) {
    const name = asset.name?.trim();
    if (!name) continue;
    const safe = escapeReg(name);
    if (new RegExp(`\\(@${safe}\\)|@${safe}`).test(text) && !seen.has(asset.id)) {
      seen.add(asset.id);
      found.push(asset);
    }
  }
  return found;
}

export interface AssetMentionFieldProps {
  value: string;
  assets: Asset[];
  rows?: number;
  readOnly?: boolean;
  className?: string;
  placeholder?: string;
  /** 与提示词同一行展示的媒体（如参考图缩略图） */
  media?: ReactNode;
  /** 媒体行下方的附注（如「故事版：上参考条+下五格」） */
  footer?: ReactNode;
  onChange: (value: string) => void;
  onBlur?: () => void;
}

/** 提示词输入：可点选插入 @资产，并展示已引用芯片 */
export function AssetMentionField({
  value,
  assets,
  rows = 3,
  readOnly,
  className,
  placeholder,
  media,
  footer,
  onChange,
  onBlur,
}: AssetMentionFieldProps) {
  const mentions = useMemo(() => parseAssetMentions(value || '', assets), [value, assets]);

  const insertMention = (asset: Asset) => {
    if (readOnly) return;
    const token = `(@${asset.name})`;
    const next = value?.includes(token) || value?.includes(`@${asset.name}`)
      ? value
      : `${(value || '').trim()}${value?.trim() ? ' ' : ''}${token}`;
    onChange(next);
  };

  const textArea = (
    <TextArea
      rows={rows}
      value={value}
      readOnly={readOnly}
      className={className}
      placeholder={placeholder || '可用上方标签插入 (@资产名)'}
      onChange={(e) => onChange(e.target.value)}
      onBlur={onBlur}
    />
  );

  const mentionBar =
    mentions.length > 0 ? (
      <div className="workspace-mention-bar" aria-label="已引用资产">
        <span className="workspace-mention-insert-label">已引用</span>
        {mentions.map((asset) => (
          <span key={`m-${asset.id}`} className="workspace-mention-chip workspace-mention-chip--active">
            (@{asset.name})
          </span>
        ))}
      </div>
    ) : null;

  return (
    <div className="workspace-mention-field">
      {assets.length > 0 && (
        <div className="workspace-mention-insert" aria-label="插入资产引用">
          <span className="workspace-mention-insert-label">插入</span>
          <div className="workspace-mention-insert-chips">
            {assets.map((asset) => {
              const active = mentions.some((m) => m.id === asset.id);
              return (
                <button
                  key={asset.id}
                  type="button"
                  className={`workspace-mention-chip${active ? ' workspace-mention-chip--active' : ''}`}
                  disabled={readOnly}
                  onClick={() => insertMention(asset)}
                >
                  <span className="workspace-mention-chip-at">@{asset.name}</span>
                  <span className="workspace-mention-chip-type">{typeLabel[asset.type] || ''}</span>
                </button>
              );
            })}
          </div>
        </div>
      )}
      {media ? (
        <div className="workspace-mention-media-row">
          <div className="workspace-mention-media">{media}</div>
          <div className="workspace-mention-editor">
            {textArea}
            {mentionBar}
            {footer}
          </div>
        </div>
      ) : (
        <>
          {textArea}
          {mentionBar}
          {footer}
        </>
      )}
    </div>
  );
}
