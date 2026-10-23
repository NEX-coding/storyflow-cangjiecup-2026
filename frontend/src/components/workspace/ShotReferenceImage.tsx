import { useEffect, useMemo, useState } from 'react';
import { Image, Spin, Typography } from 'antd';
import { PictureOutlined } from '@ant-design/icons';
import type { ReferencePrompt } from '../../types';
import { authHeaders } from '../../lib/auth';
import { resolveAssetPublicUrl } from './AssetReferenceImage';

const apiBase = (import.meta.env.VITE_API_BASE as string | undefined) || '/api';

export function shotRefImageUrl(
  projectId: number,
  shotId: number,
  refId: number,
  cacheKey?: string,
) {
  const base = apiBase.endsWith('/') ? apiBase.slice(0, -1) : apiBase;
  const url = `${base}/projects/${projectId}/shots/${shotId}/refs/${refId}/image`;
  return cacheKey ? `${url}?v=${encodeURIComponent(cacheKey)}` : url;
}

function resolveShotRefPublicUrl(ref: Pick<ReferencePrompt, 'referenceImageUrl' | 'referenceImageFile'>) {
  if (ref.referenceImageUrl || ref.referenceImageFile) {
    return resolveAssetPublicUrl({
      referenceImageUrl: ref.referenceImageUrl,
      referenceImageFile: ref.referenceImageFile,
    });
  }
  return null;
}

export interface ShotReferenceImageProps {
  projectId: number;
  shotId: number;
  /** 不可命名为 ref：React 18 会把它当成 DOM ref，不会作为 props 传入 */
  reference: ReferencePrompt;
  loading?: boolean;
  compact?: boolean;
}

const refTypeLabels: Record<string, string> = {
  first_frame: '首帧',
  last_frame: '尾帧',
  other: '其他',
};

export function ShotReferenceImage({
  projectId,
  shotId,
  reference,
  loading = false,
  compact = false,
}: ShotReferenceImageProps) {
  const hasImage = Boolean(reference?.referenceImageUrl || reference?.referenceImageFile);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [publicFailed, setPublicFailed] = useState(false);

  const publicSrc = useMemo(
    () => (reference ? resolveShotRefPublicUrl(reference) : null),
    [reference?.referenceImageFile, reference?.referenceImageUrl],
  );

  useEffect(() => {
    setPublicFailed(false);
  }, [reference?.id, reference?.referenceImageFile, reference?.referenceImageUrl]);

  useEffect(() => {
    if (!reference || (publicSrc && !publicFailed) || !hasImage || loading) {
      setBlobUrl(null);
      setLoadError(false);
      return;
    }

    let cancelled = false;
    let objectUrl: string | null = null;

    const load = async () => {
      const url = shotRefImageUrl(projectId, shotId, reference.id, reference.referenceImageFile);
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          const response = await fetch(url, {
            credentials: 'include',
            headers: authHeaders(),
          });
          if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
          }
          const blob = await response.blob();
          objectUrl = URL.createObjectURL(blob);
          if (!cancelled) {
            setBlobUrl(objectUrl);
            setLoadError(false);
          } else {
            URL.revokeObjectURL(objectUrl);
          }
          return;
        } catch {
          if (attempt < 2) {
            await new Promise((r) => window.setTimeout(r, 800 * (attempt + 1)));
          }
        }
      }
      if (!cancelled) {
        setBlobUrl(null);
        setLoadError(true);
      }
    };

    void load();

    return () => {
      cancelled = true;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [hasImage, loading, projectId, shotId, reference?.id, reference?.referenceImageFile, publicSrc, publicFailed]);

  const wrapClass = [
    'workspace-shot-ref-image',
    compact ? 'workspace-shot-ref-image--compact' : '',
  ]
    .filter(Boolean)
    .join(' ');

  if (!reference) {
    return null;
  }

  const label = refTypeLabels[reference.type] || '参考图';

  const body = (() => {
    if (loading) {
      return (
        <div className={`${wrapClass} workspace-shot-ref-image--loading`}>
          <Spin size="small" />
          {!compact && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              生成{label}中…
            </Typography.Text>
          )}
        </div>
      );
    }

    if (hasImage && publicSrc && !publicFailed) {
      return (
        <div className={wrapClass}>
          <Image
            src={publicSrc}
            alt={`${label}参考图`}
            className="workspace-shot-ref-image__img"
            rootClassName="workspace-shot-ref-image__preview"
            onError={() => setPublicFailed(true)}
          />
        </div>
      );
    }

    if (hasImage && blobUrl) {
      return (
        <div className={wrapClass}>
          <Image
            src={blobUrl}
            alt={`${label}参考图`}
            className="workspace-shot-ref-image__img"
            rootClassName="workspace-shot-ref-image__preview"
          />
        </div>
      );
    }

    if (hasImage && loadError) {
      return (
        <div className={`${wrapClass} workspace-shot-ref-image--empty`}>
          <PictureOutlined className="workspace-shot-ref-image__placeholder-icon" />
          {!compact && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              加载失败
            </Typography.Text>
          )}
        </div>
      );
    }

    if (hasImage) {
      return (
        <div className={`${wrapClass} workspace-shot-ref-image--loading`}>
          <Spin size="small" />
        </div>
      );
    }

    return (
      <div className={`${wrapClass} workspace-shot-ref-image--empty`}>
        <PictureOutlined className="workspace-shot-ref-image__placeholder-icon" />
        {!compact && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            未生成
          </Typography.Text>
        )}
      </div>
    );
  })();

  return body;
}
