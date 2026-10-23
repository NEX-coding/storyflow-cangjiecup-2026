import { useCallback, useEffect, useState } from 'react';
import {
  imageGenQueue,
  type ImageGenJob,
} from '../lib/imageGenQueue';

export function useImageGenQueue() {
  const [snap, setSnap] = useState(() => imageGenQueue.getSnapshot());

  useEffect(() => imageGenQueue.subscribe(() => setSnap(imageGenQueue.getSnapshot())), []);

  const enqueue = useCallback((job: ImageGenJob) => imageGenQueue.enqueue(job), []);

  return {
    ...snap,
    enqueue,
    isPending: (id: string) => snap.pendingIds.includes(id),
    isActive: (id: string) => snap.activeId === id,
    isQueued: (id: string) => snap.queuedIds.includes(id),
  };
}
