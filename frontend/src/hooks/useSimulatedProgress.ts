import { useEffect, useRef, useState } from 'react';

/** 无服务端进度时显示估算进度；完成状态由任务结果决定 */
export function useSimulatedProgress(active: boolean, estimateMs = 120_000) {
  const [progress, setProgress] = useState(0);
  const activeRef = useRef(false);

  useEffect(() => {
    if (!active) {
      if (activeRef.current) {
        setProgress(100);
        const done = window.setTimeout(() => setProgress(0), 400);
        activeRef.current = false;
        return () => window.clearTimeout(done);
      }
      setProgress(0);
      return;
    }

    activeRef.current = true;
    setProgress(3);
    const start = Date.now();
    const timer = window.setInterval(() => {
      const ratio = (Date.now() - start) / estimateMs;
      const next = Math.min(96, 3 + 93 * (1 - Math.exp(-ratio * 2)));
      setProgress(Math.round(next));
    }, 120);

    return () => window.clearInterval(timer);
  }, [active, estimateMs]);

  return progress;
}
