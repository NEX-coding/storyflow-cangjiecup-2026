import axios from 'axios';
import type {
  AiConfigForm,
  AiHealthStatus,
  AiHistoryItem,
  AiHistoryType,
  AiModelConfig,
  ApiResponse,
  Asset,
  Feedback,
  FeedbackForm,
  Project,
  ProjectSnapshot,
  ScriptEntry,
  Shot,
  ShotVideo,
  ReferencePrompt,
} from '../types';
import type { ExportProjectPayload } from '../lib/exportProject';
import { authHeaders, type AuthResult, type AuthUser } from '../lib/auth';
import { personalAiHeaders } from '../lib/personalAi';

const apiBase = (import.meta.env.VITE_API_BASE as string | undefined) || '/api';

const client = axios.create({
  baseURL: apiBase,
  timeout: 600000,
  withCredentials: true,
});

client.interceptors.request.use((config) => {
  const headers = { ...authHeaders(), ...personalAiHeaders() };
  Object.entries(headers).forEach(([key, value]) => {
    config.headers.set(key, value);
  });
  return config;
});

client.interceptors.response.use(
  (res) => {
    const body = res.data as ApiResponse<unknown>;
    if (body.code !== 200) {
      return Promise.reject(new Error(body.message || '请求失败'));
    }
    return res;
  },
  (err) => Promise.reject(new Error(err.response?.data?.message || err.message)),
);

/** 解析 fetch SSE 流（event:/data: 块） */
async function consumeSse(
  body: ReadableStream<Uint8Array>,
  signal: AbortSignal | undefined,
  onEvent: (name: string, payload: Record<string, unknown>) => void,
) {
  const reader = body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];

  const flushEvent = () => {
    if (dataLines.length === 0) {
      eventName = 'message';
      return;
    }
    const raw = dataLines.join('\n');
    dataLines = [];
    const name = eventName;
    eventName = 'message';

    let payload: Record<string, unknown> = {};
    try {
      payload = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      payload = { text: raw };
    }
    onEvent(name, payload);
  };

  while (true) {
    if (signal?.aborted) {
      await reader.cancel().catch(() => undefined);
      throw new DOMException('Aborted', 'AbortError');
    }
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let nl: number;
    while ((nl = buffer.indexOf('\n')) >= 0) {
      let line = buffer.slice(0, nl);
      buffer = buffer.slice(nl + 1);
      if (line.endsWith('\r')) line = line.slice(0, -1);

      if (line === '') {
        flushEvent();
        continue;
      }
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }
  }
  flushEvent();
}

export const authApi = {
  login: (email: string, password: string) =>
    client
      .post<ApiResponse<AuthResult>>('/auth/login', { email, password })
      .then((r) => r.data.data),
  sendRegisterCode: (email: string) =>
    client.post<ApiResponse<null>>('/auth/register/send-code', { email }).then(() => undefined),
  register: (email: string, password: string, code: string, nickname?: string) =>
    client
      .post<ApiResponse<AuthResult>>('/auth/register', { email, password, code, nickname })
      .then((r) => r.data.data),
  me: () => client.get<ApiResponse<AuthUser>>('/auth/me').then((r) => r.data.data),
  logout: () => client.post<ApiResponse<null>>('/auth/logout').then((r) => r.data.data),
};

export const projectApi = {
  list: () => client.get<ApiResponse<Project[]>>('/projects').then((r) => r.data.data),
  create: (title: string, stylePreset?: string) =>
    client.post<ApiResponse<Project>>('/projects', { title, stylePreset }).then((r) => r.data.data),
  createDemo: () =>
    client.post<ApiResponse<Project>>('/projects/demo').then((r) => r.data.data),
  get: (id: number) => client.get<ApiResponse<Project>>(`/projects/${id}`).then((r) => r.data.data),
  update: (id: number, data: Partial<Project>) =>
    client.put<ApiResponse<Project>>(`/projects/${id}`, data).then((r) => r.data.data),
  remove: (id: number) => client.delete(`/projects/${id}`),
};

export const workspaceApi = {
  getSource: (projectId: number) =>
    client.get<ApiResponse<{ content: string }>>(`/projects/${projectId}/source`).then((r) => r.data.data),
  updateSource: (projectId: number, content: string) =>
    client.put<ApiResponse<{ content: string }>>(`/projects/${projectId}/source`, { content }).then((r) => r.data.data),
  generateScript: (projectId: number) =>
    client.post<ApiResponse<ScriptEntry[]>>(`/projects/${projectId}/script/generate`).then((r) => r.data.data),
  /** SSE：按块落库剧本，实时推送 status / cleared / scripts / assets / done */
  generateScriptStream: async (
    projectId: number,
    handlers: {
      onStatus?: (text: string) => void;
      onCleared?: () => void;
      onScripts?: (batch: ScriptEntry[], chunkIndex: number, chunkTotal: number) => void;
      onAssetsCleared?: () => void;
      onAssets?: (batch: Asset[], type: string, stepIndex: number, stepTotal: number) => void;
      onDone?: (payload: {
        scripts: ScriptEntry[];
        shots: Shot[];
        assets: Asset[];
      }) => void;
    },
    signal?: AbortSignal,
  ) => {
    const response = await fetch(`${apiBase}/projects/${projectId}/script/generate/stream`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'text/event-stream',
        ...authHeaders(),
        ...personalAiHeaders(),
      },
      signal,
    });
    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => '');
      throw new Error(text || `流式生成剧本失败 HTTP ${response.status}`);
    }

    await consumeSse(response.body, signal, (name, payload) => {
      if (name === 'status') {
        handlers.onStatus?.(String(payload.text ?? ''));
      } else if (name === 'cleared') {
        handlers.onCleared?.();
      } else if (name === 'scripts') {
        const items = Array.isArray(payload.items) ? (payload.items as ScriptEntry[]) : [];
        const chunkIndex = typeof payload.chunkIndex === 'number' ? payload.chunkIndex : 0;
        const chunkTotal = typeof payload.chunkTotal === 'number' ? payload.chunkTotal : 0;
        handlers.onScripts?.(items, chunkIndex, chunkTotal);
      } else if (name === 'assets_cleared') {
        handlers.onAssetsCleared?.();
      } else if (name === 'assets') {
        const items = Array.isArray(payload.items) ? (payload.items as Asset[]) : [];
        const type = String(payload.type ?? '');
        const stepIndex = typeof payload.stepIndex === 'number' ? payload.stepIndex : 0;
        const stepTotal = typeof payload.stepTotal === 'number' ? payload.stepTotal : 0;
        handlers.onAssets?.(items, type, stepIndex, stepTotal);
      } else if (name === 'done') {
        handlers.onDone?.({
          scripts: Array.isArray(payload.scripts) ? (payload.scripts as ScriptEntry[]) : [],
          shots: Array.isArray(payload.shots) ? (payload.shots as Shot[]) : [],
          assets: Array.isArray(payload.assets) ? (payload.assets as Asset[]) : [],
        });
      } else if (name === 'error') {
        throw new Error(String(payload.message ?? '生成剧本失败'));
      }
    });
  },
  listScripts: (projectId: number) =>
    client.get<ApiResponse<ScriptEntry[]>>(`/projects/${projectId}/script`).then((r) => r.data.data),
  updateScript: (projectId: number, scriptId: number, data: Partial<ScriptEntry>) =>
    client.put<ApiResponse<ScriptEntry>>(`/projects/${projectId}/script/${scriptId}`, data).then((r) => r.data.data),
  extractAssets: (projectId: number) =>
    client.post<ApiResponse<Asset[]>>(`/projects/${projectId}/assets/extract`).then((r) => r.data.data),
  /** SSE：人物→场景→道具逐步落库，实时推送 status / cleared / assets / done */
  extractAssetsStream: async (
    projectId: number,
    handlers: {
      onStatus?: (text: string) => void;
      onCleared?: () => void;
      onAssets?: (batch: Asset[], type: string, stepIndex: number, stepTotal: number) => void;
      onDone?: (assets: Asset[]) => void;
    },
    signal?: AbortSignal,
  ) => {
    const response = await fetch(`${apiBase}/projects/${projectId}/assets/extract/stream`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'text/event-stream',
        ...authHeaders(),
        ...personalAiHeaders(),
      },
      signal,
    });
    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => '');
      throw new Error(text || `流式提取资产失败 HTTP ${response.status}`);
    }

    await consumeSse(response.body, signal, (name, payload) => {
      if (name === 'status') {
        handlers.onStatus?.(String(payload.text ?? ''));
      } else if (name === 'cleared') {
        handlers.onCleared?.();
      } else if (name === 'assets') {
        const items = Array.isArray(payload.items) ? (payload.items as Asset[]) : [];
        const type = String(payload.type ?? '');
        const stepIndex = typeof payload.stepIndex === 'number' ? payload.stepIndex : 0;
        const stepTotal = typeof payload.stepTotal === 'number' ? payload.stepTotal : 0;
        handlers.onAssets?.(items, type, stepIndex, stepTotal);
      } else if (name === 'done') {
        handlers.onDone?.(Array.isArray(payload.assets) ? (payload.assets as Asset[]) : []);
      } else if (name === 'error') {
        throw new Error(String(payload.message ?? '提取资产失败'));
      }
    });
  },
  listAssets: (projectId: number) =>
    client.get<ApiResponse<Asset[]>>(`/projects/${projectId}/assets`).then((r) => r.data.data),
  createAsset: (projectId: number, data: Partial<Asset> & { name: string; type: Asset['type'] }) =>
    client.post<ApiResponse<Asset>>(`/projects/${projectId}/assets`, data).then((r) => r.data.data),
  updateAsset: (projectId: number, assetId: number, data: Partial<Asset>) =>
    client.put<ApiResponse<Asset>>(`/projects/${projectId}/assets/${assetId}`, data).then((r) => r.data.data),
  deleteAsset: (projectId: number, assetId: number) =>
    client.delete<ApiResponse<null>>(`/projects/${projectId}/assets/${assetId}`).then((r) => r.data.data),
  regenerateAssetPrompt: (projectId: number, assetId: number) =>
    client.post<ApiResponse<Asset>>(`/projects/${projectId}/assets/${assetId}/regenerate-prompt`).then((r) => r.data.data),
  generateAssetImage: (
    projectId: number,
    assetId: number,
    data?: { prompt?: string; width?: number; height?: number; numInferenceSteps?: number },
  ) =>
    client
      .post<ApiResponse<Asset>>(`/projects/${projectId}/assets/${assetId}/generate-image`, data ?? {})
      .then((r) => r.data.data),
  generateAssetImagesBatch: (projectId: number, assetIds?: number[]) =>
    client
      .post<ApiResponse<Asset[]>>(`/projects/${projectId}/assets/generate-images`, {
        assetIds: assetIds ?? [],
      })
      .then((r) => r.data.data),
  generateShots: (projectId: number) =>
    client.post<ApiResponse<Shot[]>>(`/projects/${projectId}/shots/generate`).then((r) => r.data.data),
  listShots: (projectId: number) =>
    client.get<ApiResponse<Shot[]>>(`/projects/${projectId}/shots`).then((r) => r.data.data),
  reorderShots: (projectId: number, shotIds: number[]) =>
    client
      .put<ApiResponse<Shot[]>>(`/projects/${projectId}/shots/reorder`, { shotIds })
      .then((r) => r.data.data),
  insertShot: (projectId: number, afterShotId?: number | null) =>
    client
      .post<ApiResponse<Shot[]>>(`/projects/${projectId}/shots/insert`, { afterShotId: afterShotId ?? null })
      .then((r) => r.data.data),
  mergeShots: (projectId: number, primaryShotId: number, secondaryShotId: number) =>
    client
      .post<ApiResponse<Shot[]>>(`/projects/${projectId}/shots/merge`, { primaryShotId, secondaryShotId })
      .then((r) => r.data.data),
  splitShot: (projectId: number, shotId: number, splitAt?: number) =>
    client
      .post<ApiResponse<Shot[]>>(`/projects/${projectId}/shots/${shotId}/split`, splitAt != null ? { splitAt } : {})
      .then((r) => r.data.data),
  deleteShot: (projectId: number, shotId: number) =>
    client.delete<ApiResponse<Shot[]>>(`/projects/${projectId}/shots/${shotId}`).then((r) => r.data.data),
  updateShot: (projectId: number, shotId: number, data: Partial<Shot>) =>
    client.put<ApiResponse<Shot>>(`/projects/${projectId}/shots/${shotId}`, data).then((r) => r.data.data),
  regenerateShotPrompts: (projectId: number, shotId: number, options?: { snapshot?: boolean }) =>
    client
      .post<ApiResponse<Shot[]>>(`/projects/${projectId}/shots/${shotId}/regenerate-prompts`, null, {
        params: { snapshot: options?.snapshot ?? true },
      })
      .then((r) => r.data.data),
  prepareShotsBatch: (projectId: number, data?: { shotIds?: number[]; stylePreset?: string }) =>
    client
      .post<ApiResponse<Shot[]>>(`/projects/${projectId}/shots/regenerate-batch`, data ?? {})
      .then((r) => r.data.data),
  /**
   * 设置参考方式：
   * - 传 shotIds：只改这些分镜覆盖
   * - 否则改作品默认；syncAllShots 时清除全部覆盖
   */
  applyVideoRefMode: (
    projectId: number,
    data: { mode: string; syncAllShots?: boolean; shotIds?: number[] },
  ) =>
    client
      .post<
        ApiResponse<{
          mode: string;
          clearedShotOverrides: number;
          updatedShotCount: number;
          shots: Shot[];
        }>
      >(`/projects/${projectId}/shots/apply-video-ref-mode`, {
        mode: data.mode,
        syncAllShots: data.syncAllShots ?? true,
        shotIds: data.shotIds,
      })
      .then((r) => r.data.data),
  updateReferencePrompt: (
    projectId: number,
    shotId: number,
    refId: number,
    data: Partial<ReferencePrompt>,
  ) =>
    client
      .put<ApiResponse<ReferencePrompt>>(`/projects/${projectId}/shots/${shotId}/refs/${refId}`, data)
      .then((r) => r.data.data),
  generateShotReferenceImage: (
    projectId: number,
    shotId: number,
    refId: number,
    data?: { prompt?: string; width?: number; height?: number; numInferenceSteps?: number },
  ) =>
    client
      .post<ApiResponse<ReferencePrompt>>(
        `/projects/${projectId}/shots/${shotId}/refs/${refId}/generate-image`,
        data ?? {},
      )
      .then((r) => r.data.data),
  generateShotReferenceImagesBatch: (projectId: number, shotId: number) =>
    client
      .post<ApiResponse<ReferencePrompt[]>>(
        `/projects/${projectId}/shots/${shotId}/refs/generate-images`,
        {},
      )
      .then((r) => r.data.data),
  listShotVideos: (projectId: number, shotId: number) =>
    client
      .get<ApiResponse<ShotVideo[]>>(`/projects/${projectId}/shots/${shotId}/videos`)
      .then((r) => r.data.data),
  /** 彻底删除分镜视频（含媒体文件，不可恢复） */
  deleteShotVideo: (projectId: number, shotId: number, videoId: number) =>
    client
      .delete<ApiResponse<null>>(`/projects/${projectId}/shots/${shotId}/videos/${videoId}`)
      .then((r) => r.data),
  listSnapshots: (projectId: number) =>
    client
      .get<ApiResponse<ProjectSnapshot[]>>(`/projects/${projectId}/snapshots`)
      .then((r) => r.data.data),
  createSnapshot: (projectId: number, label?: string) =>
    client
      .post<ApiResponse<ProjectSnapshot>>(`/projects/${projectId}/snapshots`, { label })
      .then((r) => r.data.data),
  restoreSnapshot: (projectId: number, snapshotId: number) =>
    client
      .post<ApiResponse<ExportProjectPayload>>(`/projects/${projectId}/snapshots/${snapshotId}/restore`)
      .then((r) => r.data.data),
  export: (projectId: number) =>
    client
      .post<ApiResponse<ExportProjectPayload>>(`/projects/${projectId}/export`)
      .then((r) => r.data.data),
};

export const aiConfigApi = {
  /** 公开只读：当前对接的 AI（访客可用） */
  status: () =>
    client.get<ApiResponse<AiHealthStatus>>('/ai-models/status').then((r) => r.data.data),
  get: () => client.get<ApiResponse<AiModelConfig>>('/admin/ai-models').then((r) => r.data.data),
  save: (data: AiConfigForm) =>
    client.put<ApiResponse<AiModelConfig>>('/admin/ai-models', data).then((r) => r.data.data),
  health: () =>
    client.get<ApiResponse<AiHealthStatus>>('/admin/ai-models/health').then((r) => r.data.data),
};

export const feedbackApi = {
  list: () => client.get<ApiResponse<Feedback[]>>('/feedback').then((r) => r.data.data),
  submit: (data: FeedbackForm) =>
    client.post<ApiResponse<Feedback>>('/feedback', data).then((r) => r.data.data),
};

export const aiHistoryApi = {
  list: (type?: AiHistoryType) =>
    client
      .get<ApiResponse<AiHistoryItem[]>>('/ai-history', { params: type ? { type } : undefined })
      .then((r) => r.data.data),
};

