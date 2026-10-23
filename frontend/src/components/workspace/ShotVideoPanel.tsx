import { useState } from 'react';
import { Typography, message } from 'antd';
import { PictureOutlined } from '@ant-design/icons';
import type { ReferencePrompt, Shot, VideoRefMode } from '../../types';
import { workspaceApi } from '../../api/client';
import { refCardTitle, visibleRefsForMode } from '../../lib/videoRefMode';
import { ProgressButton } from './ProgressButton';
import { ShotReferenceImage } from './ShotReferenceImage';
import { useImageGenQueue } from '../../hooks/useImageGenQueue';
import { shotRefImageJobId } from '../../lib/imageGenQueue';

export interface ShotVideoPanelProps {
  projectId: number;
  shot: Shot;
  /** 已解析的有效参考方式（含作品默认） */
  videoRefMode?: VideoRefMode;
  readOnly?: boolean;
  onShotChange?: (shot: Shot) => void;
}

/** 分镜参考图区。 */
export function ShotVideoPanel({
  projectId,
  shot,
  videoRefMode = 'first_last',
  readOnly = false,
  onShotChange,
}: ShotVideoPanelProps) {
  const [batchRefs, setBatchRefs] = useState(false);
  const {
    enqueue: enqueueImageGen,
    isActive: isImageGenActive,
    isQueued: isImageGenQueued,
    isPending: isImageGenPending,
  } = useImageGenQueue();

  const visibleRefs = visibleRefsForMode(shot.referencePrompts, videoRefMode);
  const showRefGallery = videoRefMode !== 'none';

  const updateRef = (updated: ReferencePrompt, baseRefs = shot.referencePrompts) => {
    if (!onShotChange) return;
    onShotChange({
      ...shot,
      referencePrompts: baseRefs.map((r) => (r.id === updated.id ? updated : r)),
    });
  };

  const handleGenerateRef = (ref: ReferencePrompt) => {
    if (!ref.prompt?.trim()) {
      message.warning('请先填写参考图提示词');
      return;
    }
    const title = refCardTitle(ref, videoRefMode);
    const jobId = shotRefImageJobId(shot.id, ref.id);
    const status = enqueueImageGen({
      id: jobId,
      label: title,
      run: async () => {
        try {
          const updated = await workspaceApi.generateShotReferenceImage(projectId, shot.id, ref.id);
          updateRef(updated);
          message.success(`${title}已生成`);
        } catch (e) {
          message.error(e instanceof Error ? e.message : '参考图生成失败');
        }
      },
    });
    if (status === 'queued') {
      message.info(`${title}已加入生图队列`);
    } else if (status === 'duplicate') {
      message.info(`${title}已在队列中`);
    }
  };

  const handleGenerateAllRefs = () => {
    const targets = visibleRefs.filter((r) => r.prompt?.trim());
    if (targets.length === 0) {
      message.warning('请先填写参考图提示词');
      return;
    }
    const batchIds = new Set<string>();
    let done = 0;
    let success = 0;
    let accumulatedRefs = shot.referencePrompts;
    for (const ref of targets) {
      const title = refCardTitle(ref, videoRefMode);
      const jobId = shotRefImageJobId(shot.id, ref.id);
      const status = enqueueImageGen({
        id: jobId,
        label: title,
        run: async () => {
          try {
            const updated = await workspaceApi.generateShotReferenceImage(
              projectId,
              shot.id,
              ref.id,
            );
            accumulatedRefs = accumulatedRefs.map((r) => (r.id === updated.id ? updated : r));
            updateRef(updated, accumulatedRefs);
            success += 1;
          } catch (e) {
            message.error(
              `${title}生成失败：${e instanceof Error ? e.message : '未知错误'}`,
            );
          } finally {
            if (!batchIds.has(jobId)) return;
            done += 1;
            if (done >= batchIds.size) {
              setBatchRefs(false);
              if (success > 0) {
                message.success(`已生成 ${success}/${batchIds.size} 张参考图`);
              }
            }
          }
        },
      });
      if (status !== 'duplicate') {
        batchIds.add(jobId);
      }
    }
    if (batchIds.size === 0) {
      message.info('参考图均已在生图队列中');
      return;
    }
    setBatchRefs(true);
    message.info(`已加入生图队列 ${batchIds.size} 张，将依次生成`);
  };

  if (!showRefGallery) {
    return (
      <div className="workspace-shot-video-panel">
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          当前为无参考模式
        </Typography.Text>
      </div>
    );
  }

  return (
    <div className="workspace-shot-video-panel">
      <div className="workspace-shot-ref-gallery">
        <div className="workspace-shot-ref-gallery-header">
          <span className="workspace-field-label" style={{ marginBottom: 0 }}>
            分镜参考图
          </span>
          {!readOnly && visibleRefs.length > 0 && (
            <ProgressButton
              size="small"
              icon={<PictureOutlined />}
              progressing={batchRefs}
              estimateMs={visibleRefs.length * 20_000}
              onClick={() => handleGenerateAllRefs()}
            >
              {visibleRefs.length > 1 ? '全部生成' : '生成参考图'}
            </ProgressButton>
          )}
        </div>
        {visibleRefs.length === 0 ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {videoRefMode === 'single'
              ? '请先生成分镜提示词（含故事版）'
              : '请先生成分镜提示词（含首帧/尾帧参考图提示词）'}
          </Typography.Text>
        ) : (
          <div
            className={`workspace-shot-ref-gallery-grid${
              videoRefMode === 'single' ? ' workspace-shot-ref-gallery-grid--single' : ''
            }`}
          >
            {visibleRefs.map((ref) => (
              <div key={ref.id} className="workspace-shot-ref-gallery-item">
                <div className="workspace-shot-ref-gallery-item-label">
                  {refCardTitle(ref, videoRefMode)}
                </div>
                <ShotReferenceImage
                  projectId={projectId}
                  shotId={shot.id}
                  reference={ref}
                  loading={isImageGenPending(shotRefImageJobId(shot.id, ref.id))}
                />
                {!readOnly && (
                  <ProgressButton
                    size="small"
                    block
                    progressing={isImageGenActive(shotRefImageJobId(shot.id, ref.id))}
                    queued={isImageGenQueued(shotRefImageJobId(shot.id, ref.id))}
                    estimateMs={20_000}
                    disabled={!ref.prompt?.trim()}
                    onClick={() => handleGenerateRef(ref)}
                  >
                    生成
                  </ProgressButton>
                )}
              </div>
            ))}
          </div>
        )}
        <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 6 }}>
          {videoRefMode === 'single'
            ? '故事版静帧，用于短剧分镜预览'
            : '短剧分镜参考图（首帧 / 尾帧）'}
        </Typography.Text>
      </div>
    </div>
  );
}
