import { useEffect, useMemo, useState } from 'react';
import { Image, Spin, Typography } from 'antd';
import { PictureOutlined } from '@ant-design/icons';
import type { Asset } from '../../types';
import { authHeaders } from '../../lib/auth';

const apiBase = (import.meta.env.VITE_API_BASE as string | undefined) || '/api';

export function assetImageUrl(projectId: number, assetId: number, cacheKey?: string) {
  const base = apiBase.endsWith('/') ? apiBase.slice(0, -1) : apiBase;
  const url = `${base}/projects/${projectId}/assets/${assetId}/image`;
  return cacheKey ? `${url}?v=${encodeURIComponent(cacheKey)}` : url;
}

function isPublicAssetUrl(url?: string) {
  return Boolean(url && url.startsWith('/storyflow/asset-media/'));
}

/** 公网直链：优先 referenceImageUrl，否则用 referenceImageFile 拼路径 */
export function resolveAssetPublicUrl(asset: Pick<Asset, 'referenceImageUrl' | 'referenceImageFile'>) {
  const cache = asset.referenceImageFile
    ? `?v=${encodeURIComponent(asset.referenceImageFile)}`
    : '';
  if (asset.referenceImageUrl && isPublicAssetUrl(asset.referenceImageUrl)) {
    return `${asset.referenceImageUrl}${cache}`;
  }
  if (asset.referenceImageFile) {
    const file = encodeURIComponent(asset.referenceImageFile);
    return `/storyflow/asset-media/${file}${cache}`;
  }
  return null;
}

export interface AssetReferenceImageProps {
  projectId: number;
  asset: Asset;
  loading?: boolean;
  /** 96×96，资产卡片 / 弹层 */
  compact?: boolean;
  /** 48×48，分镜关联资产行内 */
  inline?: boolean;
}

export function AssetReferenceImage({
  projectId,
  asset,
  loading = false,
  compact = false,
  inline = false,
}: AssetReferenceImageProps) {
  const hasImage = Boolean(asset.referenceImageUrl || asset.referenceImageFile);
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [publicFailed, setPublicFailed] = useState(false);

  const publicSrc = useMemo(
    () => resolveAssetPublicUrl(asset),
    [asset.referenceImageFile, asset.referenceImageUrl],
  );

  useEffect(() => {
    setPublicFailed(false);
  }, [asset.id, asset.referenceImageFile, asset.referenceImageUrl]);

  useEffect(() => {
    if ((publicSrc && !publicFailed) || !hasImage || loading) {
      setBlobUrl(null);
      setLoadError(false);
      return;
    }

    let cancelled = false;
    let objectUrl: string | null = null;

    const load = async () => {
      const url = assetImageUrl(projectId, asset.id, asset.referenceImageFile);
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
  }, [hasImage, loading, projectId, asset.id, asset.referenceImageFile, publicSrc, publicFailed]);

  const wrapClass = [
    'workspace-asset-ref-image',
    inline ? 'workspace-asset-ref-image--inline' : '',
    compact ? 'workspace-asset-ref-image--compact' : '',
  ]
    .filter(Boolean)
    .join(' ');

  if (loading) {
    return (
      <div className={`${wrapClass} workspace-asset-ref-image--loading`}>
        <Spin size="small" />
        {!inline && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            生成参考图中…
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
          alt={`${asset.name} 参考图`}
          className="workspace-asset-ref-image__img"
          rootClassName="workspace-asset-ref-image__preview"
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
          alt={`${asset.name} 参考图`}
          className="workspace-asset-ref-image__img"
          rootClassName="workspace-asset-ref-image__preview"
        />
      </div>
    );
  }

  if (hasImage && loadError) {
    return (
      <div className={`${wrapClass} workspace-asset-ref-image--empty`}>
        <PictureOutlined className="workspace-asset-ref-image__placeholder-icon" />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          参考图加载失败，请重新生成
        </Typography.Text>
      </div>
    );
  }

  if (hasImage) {
    return (
      <div className={`${wrapClass} workspace-asset-ref-image--loading`}>
        <Spin size="small" />
      </div>
    );
  }

  return (
    <div className={`${wrapClass} workspace-asset-ref-image--empty`}>
      <PictureOutlined
        className="workspace-asset-ref-image__placeholder-icon"
        style={inline ? { fontSize: 16 } : undefined}
      />
      {!inline && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          暂无参考图
        </Typography.Text>
      )}
    </div>
  );
}
