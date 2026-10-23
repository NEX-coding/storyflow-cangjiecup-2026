import axios from 'axios';
import { authHeaders } from '../../lib/auth';
import { personalAiHeaders } from '../../lib/personalAi';
import type { ScriptEntry } from '../../types';
import type {
  AssistantChatRequest,
  AssistantMessage,
  AssistantStreamHandlers,
} from './types';

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

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

function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === 'AbortError') ||
    (error instanceof Error && error.name === 'AbortError')
  );
}

async function parseSseStream(
  response: Response,
  handlers: AssistantStreamHandlers,
  signal?: AbortSignal,
): Promise<AssistantMessage | null> {
  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => '');
    throw new Error(text || `流式请求失败 HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];
  let donePayload: AssistantMessage | null = null;

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

    if (name === 'token') {
      handlers.onToken?.(String(payload.text ?? ''));
    } else if (name === 'status') {
      handlers.onStatus?.(String(payload.text ?? ''));
    } else if (name === 'source') {
      handlers.onSource?.(String(payload.content ?? ''));
    } else if (name === 'shots') {
      const phaseRaw = String(payload.phase ?? 'updated');
      const phase =
        phaseRaw === 'placeholders' || phaseRaw === 'streaming' || phaseRaw === 'updated'
          ? phaseRaw
          : 'updated';
      const shotNumber =
        typeof payload.shotNumber === 'number' ? payload.shotNumber : undefined;
      const videoPrompt =
        typeof payload.videoPrompt === 'string' ? payload.videoPrompt : undefined;
      handlers.onShots?.({ phase, shotNumber, videoPrompt });
    } else if (name === 'scripts') {
      const phaseRaw = String(payload.phase ?? 'append');
      const phase =
        phaseRaw === 'cleared' || phaseRaw === 'append' || phaseRaw === 'updated'
          ? phaseRaw
          : 'append';
      handlers.onScripts?.({
        phase,
        items: Array.isArray(payload.items) ? (payload.items as ScriptEntry[]) : undefined,
      });
    } else if (name === 'done') {
      donePayload = payload as unknown as AssistantMessage;
      handlers.onDone?.(donePayload);
    } else if (name === 'error') {
      throw new Error(String(payload.message ?? '流式生成失败'));
    }
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
  return donePayload;
}

/** 助手模块独立 API，不依赖 workspaceApi */
export const assistantApi = {
  chat: (projectId: number, payload: AssistantChatRequest) =>
    client
      .post<ApiResponse<AssistantMessage>>(`/projects/${projectId}/assistant/chat`, {
        focus: 'global',
        ...payload,
      })
      .then((r) => r.data.data),

  chatStream: async (
    projectId: number,
    payload: AssistantChatRequest,
    handlers: AssistantStreamHandlers,
    signal?: AbortSignal,
  ) => {
    const response = await fetch(`${apiBase}/projects/${projectId}/assistant/chat/stream`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...authHeaders(),
        ...personalAiHeaders(),
      },
      body: JSON.stringify({ focus: 'global', ...payload }),
      signal,
    });
    return parseSseStream(response, handlers, signal);
  },

  history: (projectId: number) =>
    client
      .get<ApiResponse<AssistantMessage[]>>(`/projects/${projectId}/assistant/history`)
      .then((r) => r.data.data),
};

export { isAbortError };
