import { Button, type ButtonProps } from 'antd';
import { useSimulatedProgress } from '../../hooks/useSimulatedProgress';

export interface ProgressButtonProps extends ButtonProps {
  progressing?: boolean;
  /** 已入队、尚未轮到（与 progressing 互斥展示） */
  queued?: boolean;
  queuedLabel?: string;
  /** 预估完成时间，用于显示估算进度 */
  estimateMs?: number;
  progressPrefix?: string;
  /** 传入则显示服务端进度（0–100），否则显示估算进度 */
  progress?: number;
}

export function ProgressButton({
  progressing = false,
  queued = false,
  queuedLabel = '排队中',
  estimateMs = 120_000,
  progressPrefix = '生成中',
  progress: externalProgress,
  children,
  className,
  disabled,
  loading: _loading,
  ...rest
}: ProgressButtonProps) {
  const simulated = useSimulatedProgress(progressing && externalProgress == null, estimateMs);
  const progress = externalProgress ?? simulated;
  const showProgress = progressing || progress > 0;
  const showQueued = queued && !progressing;

  return (
    <Button
      {...rest}
      className={[
        'sf-progress-btn',
        showProgress ? 'sf-progress-btn--active' : '',
        showQueued ? 'sf-progress-btn--queued' : '',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      disabled={disabled || progressing || showQueued}
      loading={false}
    >
      {showProgress && (
        <span className="sf-progress-btn__fill" style={{ width: `${progress}%` }} aria-hidden />
      )}
      <span className="sf-progress-btn__label">
        {showProgress ? `${progressPrefix} ${progress}%` : showQueued ? queuedLabel : children}
      </span>
    </Button>
  );
}
