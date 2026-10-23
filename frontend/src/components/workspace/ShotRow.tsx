import { useEffect, useState } from 'react';
import {
  Button,
  Checkbox,
  Input,
  Popconfirm,
  Popover,
  Segmented,
  Space,
  Spin,
  Tag,
  Typography,
  Tooltip,
  message,
} from 'antd';
import {
  HolderOutlined,
  PlusOutlined,
  SplitCellsOutlined,
  MergeCellsOutlined,
  DeleteOutlined,
  CopyOutlined,
} from '@ant-design/icons';
import type { Asset, ScriptEntry, Shot, VideoRefMode } from '../../types';
import { workspaceApi } from '../../api/client';
import { copyText } from '../../lib/exportProject';
import {
  VIDEO_REF_MODE_LABELS,
  VIDEO_REF_MODE_OPTIONS,
  refCardTitle,
  resolveVideoRefMode,
  visibleRefsForMode,
} from '../../lib/videoRefMode';
import { AssetReferenceImage } from './AssetReferenceImage';
import { ProgressButton } from './ProgressButton';
import { AssetMentionField } from './AssetMentionField';
import { ShotReferenceImage } from './ShotReferenceImage';
import { ShotVideoPanel } from './ShotVideoPanel';

const { TextArea } = Input;

const assetTypeLabels: Record<string, string> = {
  character: '人物',
  scene: '场景',
  prop: '道具',
};

function composeScriptText(script: ScriptEntry): string {
  const parts: string[] = [];
  const characters = (script.characters || []).filter(Boolean).join('、');
  if (characters) parts.push(characters);
  if (script.action?.trim()) parts.push(script.action.trim());
  if (script.dialogue?.trim()) parts.push(script.dialogue.trim());
  if (script.shot?.trim()) parts.push(script.shot.trim());
  return parts.join('\n\n');
}

function LinkedAssetPopoverContent({ projectId, asset }: { projectId: number; asset: Asset }) {
  return (
    <div className="workspace-linked-asset-popover">
      <div className="workspace-linked-asset-popover-name">{asset.name}</div>
      <div className="workspace-linked-asset-popover-section">
        <div className="workspace-linked-asset-popover-label">资产图</div>
        <AssetReferenceImage projectId={projectId} asset={asset} compact />
      </div>
      {asset.description && (
        <div className="workspace-linked-asset-popover-section">
          <div className="workspace-linked-asset-popover-label">介绍</div>
          <div className="workspace-linked-asset-popover-text">{asset.description}</div>
        </div>
      )}
      {asset.imagePrompt && (
        <div className="workspace-linked-asset-popover-section">
          <div className="workspace-linked-asset-popover-label">生图提示词</div>
          <div className="workspace-linked-asset-popover-text workspace-linked-asset-popover-prompt">
            {asset.imagePrompt}
          </div>
        </div>
      )}
      {!asset.description && !asset.imagePrompt && (
        <div className="workspace-linked-asset-popover-text">暂无更多信息</div>
      )}
    </div>
  );
}

export interface ShotRowProps {
  projectId: number;
  index: number;
  shot?: Shot;
  script?: ScriptEntry;
  linkedAssets: Asset[];
  /** 作品默认参考方式，分镜未覆盖时使用 */
  projectRefPromptMode?: string | null;
  canMergeNext?: boolean;
  dragDisabled?: boolean;
  isDragging?: boolean;
  isDropTarget?: boolean;
  selected?: boolean;
  onSelectedChange?: (selected: boolean) => void;
  onScriptChange: (script: ScriptEntry) => void;
  onShotChange: (shot: Shot) => void;
  onReload: () => Promise<void>;
  onRegenerateShot: () => Promise<void>;
  onInsertAfter?: () => void;
  onMergeNext?: () => void;
  onSplit?: () => void;
  onDelete?: () => void;
  onDragStart?: (shotId: number) => void;
  onDragOver?: (shotId: number) => void;
  onDrop?: (shotId: number) => void;
  onDragEnd?: () => void;
  generating?: boolean;
  regeneratingPrompt?: boolean;
  readOnly?: boolean;
}

export function ShotRow({
  projectId,
  index,
  shot,
  script,
  linkedAssets,
  projectRefPromptMode,
  canMergeNext = false,
  dragDisabled = false,
  isDragging = false,
  isDropTarget = false,
  selected = false,
  onSelectedChange,
  onScriptChange,
  onShotChange,
  onReload: _onReload,
  onRegenerateShot,
  onInsertAfter,
  onMergeNext,
  onSplit,
  onDelete,
  onDragStart,
  onDragOver,
  onDrop,
  onDragEnd,
  generating = false,
  regeneratingPrompt = false,
  readOnly = false,
}: ShotRowProps) {
  const [scriptText, setScriptText] = useState(() => (script ? composeScriptText(script) : ''));
  /** 提示词区：参考图 / 视频 切换，避免两栏堆叠占高 */
  const [promptTab, setPromptTab] = useState<'ref' | 'video'>('ref');
  const [savingRefMode, setSavingRefMode] = useState(false);

  useEffect(() => {
    setScriptText(script ? composeScriptText(script) : '');
  }, [script?.id, script?.action, script?.dialogue, script?.shot, script?.characters]);

  useEffect(() => {
    if (generating) setPromptTab('video');
  }, [generating]);

  const videoRefMode = resolveVideoRefMode(shot?.videoRefMode, projectRefPromptMode);
  const shotOverridesMode =
    shot?.videoRefMode != null && String(shot.videoRefMode).trim() !== '';
  const visibleRefs = visibleRefsForMode(shot?.referencePrompts, videoRefMode);

  const handleVideoRefModeChange = async (mode: VideoRefMode) => {
    if (!shot || readOnly || savingRefMode) return;
    const prev = shot.videoRefMode;
    onShotChange({ ...shot, videoRefMode: mode });
    setSavingRefMode(true);
    try {
      const updated = await workspaceApi.updateShot(projectId, shot.id, { videoRefMode: mode });
      // 后端偶发未带上 referencePrompts 时，保留本地已有提示词，避免「暂无参考图提示词」
      onShotChange({
        ...updated,
        referencePrompts:
          updated.referencePrompts?.length > 0
            ? updated.referencePrompts
            : shot.referencePrompts,
      });
      if (mode === 'none') {
        message.info('生成时将不使用参考图（已有参考图仍保留）');
      }
    } catch (e) {
      onShotChange({ ...shot, videoRefMode: prev });
      message.error(e instanceof Error ? e.message : '保存参考方式失败');
    } finally {
      setSavingRefMode(false);
    }
  };

  const handleCopy = async (text: string, tip: string) => {
    try {
      await copyText(text);
      message.success(tip);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '复制失败');
    }
  };

  const structureEnabled = Boolean(shot);

  return (
    <div
      id={shot ? `shot-block-${shot.id}` : `shot-block-index-${index + 1}`}
      data-shot-index={index + 1}
      className={[
        'workspace-shot-block',
        isDragging ? 'workspace-shot-block--dragging' : '',
        isDropTarget ? 'workspace-shot-block--drop-target' : '',
        selected ? 'workspace-shot-block--selected' : '',
      ]
        .filter(Boolean)
        .join(' ')}
      onDragOver={
        shot && !dragDisabled
          ? (e) => {
              e.preventDefault();
              onDragOver?.(shot.id);
            }
          : undefined
      }
      onDrop={
        shot && !dragDisabled
          ? (e) => {
              e.preventDefault();
              onDrop?.(shot.id);
            }
          : undefined
      }
    >
      <div className="workspace-shot-block-title">
        <div className="workspace-shot-block-title-main">
          {structureEnabled && (
            <Checkbox
              checked={selected}
              onChange={(e) => onSelectedChange?.(e.target.checked)}
              aria-label={`选择分镜 #${index + 1}`}
            />
          )}
          {structureEnabled && (
            <button
              type="button"
              className="workspace-shot-drag-handle"
              title="拖拽排序"
              draggable={!dragDisabled}
              disabled={dragDisabled}
              onDragStart={(e) => {
                if (dragDisabled || !shot) return;
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', String(shot.id));
                onDragStart?.(shot.id);
              }}
              onDragEnd={() => onDragEnd?.()}
            >
              <HolderOutlined />
            </button>
          )}
          <span className="workspace-shot-index">分镜 #{index + 1}</span>
          {generating && (
            <Tag color="processing" style={{ marginInlineEnd: 0 }}>
              生成中
            </Tag>
          )}
        </div>

        {structureEnabled && (
          <Space size={4} className="workspace-shot-structure-actions" wrap>
            <Tooltip title="在下方插入空分镜">
              <Button size="small" type="text" icon={<PlusOutlined />} onClick={onInsertAfter} />
            </Tooltip>
            <Tooltip title="拆分为两个分镜">
              <Button size="small" type="text" icon={<SplitCellsOutlined />} onClick={onSplit} />
            </Tooltip>
            <Tooltip title={canMergeNext ? '与下一个分镜合并' : '已是最后一个分镜'}>
              <Button
                size="small"
                type="text"
                icon={<MergeCellsOutlined />}
                disabled={!canMergeNext}
                onClick={onMergeNext}
              />
            </Tooltip>
            <Popconfirm
              title="删除此分镜？"
              description="关联的空剧本也会一并删除"
              okText="删除"
              cancelText="取消"
              onConfirm={onDelete}
            >
              <Tooltip title="删除分镜">
                <Button size="small" type="text" danger icon={<DeleteOutlined />} />
              </Tooltip>
            </Popconfirm>
          </Space>
        )}
      </div>

      <div className="workspace-shot-row">
        {/* 左栏：剧本 */}
        <div className="workspace-shot-cell">
          <div className="workspace-shot-cell-header">
            <span className="workspace-shot-index">剧本</span>
          </div>

          {script ? (
            <TextArea
              className="workspace-script-textarea"
              value={scriptText}
              placeholder="在此编辑本分镜剧本…"
              onChange={(e) => {
                const value = e.target.value;
                setScriptText(value);
                onScriptChange({
                  ...script,
                  action: value,
                  dialogue: '',
                  shot: '',
                  characters: [],
                });
              }}
              onBlur={async () => {
                await workspaceApi.updateScript(projectId, script.id, {
                  action: scriptText,
                  dialogue: '',
                  shot: '',
                  characters: [],
                });
              }}
            />
          ) : (
            <Typography.Text type="secondary">暂无关联剧本</Typography.Text>
          )}
        </div>

        {/* 中栏：提示词（参考图 / 视频 切换） */}
        <div className={`workspace-shot-cell${generating ? ' workspace-shot-cell--generating' : ''}`}>
          <div className="workspace-shot-cell-header workspace-shot-cell-header--prompt">
            <span className="workspace-shot-index">提示词</span>
            <div className="workspace-prompt-header-actions">
              <Segmented
                size="small"
                className="workspace-prompt-tab"
                value={promptTab}
                onChange={(v) => setPromptTab(v as 'ref' | 'video')}
                options={[
                  { label: '参考图', value: 'ref' },
                  { label: '视频', value: 'video' },
                ]}
              />
              {shot && (
                <Tooltip
                  title={
                    shotOverridesMode
                      ? `已覆盖 · ${VIDEO_REF_MODE_LABELS[videoRefMode]}`
                      : `默认 · ${VIDEO_REF_MODE_LABELS[videoRefMode]}`
                  }
                >
                  <Segmented
                    size="small"
                    className="workspace-prompt-tab workspace-ref-mode-tab"
                    value={videoRefMode}
                    disabled={readOnly || savingRefMode}
                    onChange={(v) => void handleVideoRefModeChange(v as VideoRefMode)}
                    options={VIDEO_REF_MODE_OPTIONS}
                  />
                </Tooltip>
              )}
              {generating && <Spin size="small" />}
              {shot && !generating && !readOnly && (
                <ProgressButton
                  size="small"
                  progressing={regeneratingPrompt}
                  progressPrefix="生成中"
                  estimateMs={45_000}
                  onClick={() => void onRegenerateShot()}
                >
                  重新生成
                </ProgressButton>
              )}
            </div>
          </div>

          {shot ? (
            promptTab === 'video' ? (
              <div>
                <div className="workspace-field-label workspace-field-label--with-action">
                  <span>
                    视频提示词
                    {generating && (
                      <Typography.Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
                        生成中…
                      </Typography.Text>
                    )}
                  </span>
                  <Tooltip title="复制视频提示词">
                    <Button
                      size="small"
                      type="text"
                      icon={<CopyOutlined />}
                      disabled={!shot.videoPrompt?.trim()}
                      onClick={() => handleCopy(shot.videoPrompt, '已复制视频提示词')}
                    />
                  </Tooltip>
                </div>
                <AssetMentionField
                  rows={6}
                  value={shot.videoPrompt}
                  assets={linkedAssets}
                  readOnly={generating || readOnly}
                  className={generating ? 'workspace-field-streaming' : undefined}
                  onChange={(v) => onShotChange({ ...shot, videoPrompt: v })}
                  onBlur={async () => {
                    if (generating || readOnly) return;
                    await workspaceApi.updateShot(projectId, shot.id, { videoPrompt: shot.videoPrompt });
                  }}
                />
              </div>
            ) : (
              <div className="workspace-ref-section">
                {videoRefMode === 'none' ? (
                  <Typography.Text type="secondary" className="workspace-ref-mode-hint">
                    无参考模式：只使用视频提示词（已有参考图仍保留，切回模式后可继续用）
                  </Typography.Text>
                ) : (
                  <div className="workspace-ref-card-list">
                    {visibleRefs.length ? (
                      visibleRefs.map((ref) => {
                        const title = refCardTitle(ref, videoRefMode);
                        return (
                          <div
                            key={ref.id}
                            className={`workspace-ref-prompt workspace-ref-prompt--${
                              videoRefMode === 'single' ? 'single' : ref.type || 'other'
                            }`}
                          >
                            <div className="workspace-field-label workspace-field-label--with-action">
                              <span>{title}</span>
                              <Tooltip title={`复制${title}提示词`}>
                                <Button
                                  size="small"
                                  type="text"
                                  icon={<CopyOutlined />}
                                  disabled={!ref.prompt?.trim()}
                                  onClick={() =>
                                    handleCopy(ref.prompt, `已复制${title}提示词`)
                                  }
                                />
                              </Tooltip>
                            </div>
                            <AssetMentionField
                              rows={4}
                              value={ref.prompt}
                              assets={linkedAssets}
                              readOnly={readOnly}
                              media={
                                <ShotReferenceImage
                                  projectId={projectId}
                                  shotId={shot.id}
                                  reference={ref}
                                  compact
                                />
                              }
                              footer={
                                ref.note ? (
                                  <div className="workspace-ref-card-note">
                                    {title}：
                                    {ref.note.replace(/^(分镜图版|故事版)[：:]\s*/, '')}
                                  </div>
                                ) : null
                              }
                              onChange={(v) =>
                                onShotChange({
                                  ...shot,
                                  referencePrompts: shot.referencePrompts.map((r) =>
                                    r.id === ref.id ? { ...r, prompt: v } : r,
                                  ),
                                })
                              }
                              onBlur={async () => {
                                if (readOnly) return;
                                const current = shot.referencePrompts.find((r) => r.id === ref.id);
                                if (current) {
                                  await workspaceApi.updateReferencePrompt(
                                    projectId,
                                    shot.id,
                                    ref.id,
                                    { prompt: current.prompt },
                                  );
                                }
                              }}
                            />
                          </div>
                        );
                      })
                    ) : (
                      <Typography.Text type="secondary">暂无参考图提示词</Typography.Text>
                    )}
                  </div>
                )}
              </div>
            )
          ) : (
            <Typography.Text type="secondary">请先生成分镜提示词</Typography.Text>
          )}
        </div>

        {/* 右栏：关联资产（按人物/场景/道具分组） */}
        <div className="workspace-shot-cell">
          <div className="workspace-shot-cell-header">
            <span className="workspace-shot-index">关联资产</span>
            <Tag>{linkedAssets.length}</Tag>
          </div>

          {linkedAssets.length > 0 ? (
            <div className="workspace-linked-assets">
              {(['character', 'scene', 'prop'] as const).map((type) => {
                const group = linkedAssets.filter((asset) => asset.type === type);
                if (group.length === 0) return null;
                return (
                  <div key={type} className="workspace-linked-asset-group">
                    <div className="workspace-linked-asset-group-title">{assetTypeLabels[type]}</div>
                    <div className="workspace-linked-asset-list">
                      {group.map((asset) => (
                        <Popover
                          key={asset.id}
                          trigger="hover"
                          mouseEnterDelay={0.15}
                          placement="leftTop"
                          content={<LinkedAssetPopoverContent projectId={projectId} asset={asset} />}
                        >
                          <div className="workspace-linked-asset-item">
                            <AssetReferenceImage
                              projectId={projectId}
                              asset={asset}
                              inline
                            />
                            <span className="workspace-linked-asset-item__name">{asset.name}</span>
                          </div>
                        </Popover>
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <Typography.Text type="secondary">暂无关联资产</Typography.Text>
          )}
        </div>

        {/* 右栏：分镜参考图 */}
        <div className="workspace-shot-cell workspace-shot-cell--video">
          {shot ? (
            <ShotVideoPanel
              projectId={projectId}
              shot={shot}
              videoRefMode={videoRefMode}
              readOnly={readOnly}
              onShotChange={onShotChange}
            />
          ) : (
            <>
              <div className="workspace-shot-cell-header">
                <span className="workspace-shot-index">分镜参考图</span>
              </div>
              <Typography.Text type="secondary">请先生成分镜提示词</Typography.Text>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
