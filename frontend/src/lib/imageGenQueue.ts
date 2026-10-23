/**
 * 前端生图任务队列：可连点入队，串行执行（对齐后端单卡 GPU 锁）。
 * 资产图 / 分镜故事版共用同一队列，避免并行打满 GPU。
 */

export type ImageGenJob = {
  /** 唯一键，如 asset:12 / shotRef:91:5 */
  id: string;
  label: string;
  run: () => Promise<void>;
};

type Snapshot = {
  activeId: string | null;
  /** 排队中（含正在执行） */
  pendingIds: string[];
  /** 仅排队、尚未执行 */
  queuedIds: string[];
  queueLength: number;
};

type Listener = () => void;

class ImageGenQueue {
  private queue: ImageGenJob[] = [];
  private activeId: string | null = null;
  private pending = new Set<string>();
  private listeners = new Set<Listener>();
  private pumping = false;

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  getSnapshot(): Snapshot {
    const pendingIds = [...this.pending];
    const queuedIds = pendingIds.filter((id) => id !== this.activeId);
    return {
      activeId: this.activeId,
      pendingIds,
      queuedIds,
      queueLength: this.queue.length + (this.activeId ? 1 : 0),
    };
  }

  isPending(id: string): boolean {
    return this.pending.has(id);
  }

  isActive(id: string): boolean {
    return this.activeId === id;
  }

  isQueued(id: string): boolean {
    return this.pending.has(id) && this.activeId !== id;
  }

  /**
   * @returns 'started' 立即执行；'queued' 已入队；'duplicate' 已在队列/执行中
   */
  enqueue(job: ImageGenJob): 'started' | 'queued' | 'duplicate' {
    if (this.pending.has(job.id)) {
      return 'duplicate';
    }
    this.pending.add(job.id);
    this.queue.push(job);
    const willStart = !this.activeId && this.queue.length === 1;
    this.emit();
    void this.pump();
    return willStart ? 'started' : 'queued';
  }

  private emit() {
    for (const listener of this.listeners) {
      listener();
    }
  }

  private async pump() {
    if (this.pumping) return;
    this.pumping = true;
    try {
      while (this.queue.length > 0) {
        const job = this.queue.shift();
        if (!job) break;
        this.activeId = job.id;
        this.emit();
        try {
          await job.run();
        } catch {
          // 错误由 job.run 内部处理（toast），这里不中断队列
        } finally {
          this.pending.delete(job.id);
          this.activeId = null;
          this.emit();
        }
      }
    } finally {
      this.pumping = false;
      if (this.queue.length > 0) {
        void this.pump();
      }
    }
  }
}

export const imageGenQueue = new ImageGenQueue();

export function assetImageJobId(assetId: number): string {
  return `asset:${assetId}`;
}

export function shotRefImageJobId(shotId: number, refId: number): string {
  return `shotRef:${shotId}:${refId}`;
}
