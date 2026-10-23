import { useCallback, useEffect, useRef, useState } from 'react';
import { Modal, message } from 'antd';
import { assistantApi, isAbortError } from '../api';
import type {
  AssistantFocus,
  AssistantMessage,
  ScriptsStreamEvent,
  ShotsStreamEvent,
} from '../types';

const STREAMING_ID = -1;

type DangerKind =
  | 'delete_chapter'
  | 'delete_shots'
  | 'delete_scripts'
  | 'delete_asset'
  | 'overwrite_source'
  | 'clear_source';

function looksLikeDeleteChapter(text: string): boolean {
  const hasDelete = /删除|删掉|去掉|移除/.test(text);
  const hasChapter = /章/.test(text) && /第/.test(text);
  const hasShot = /分镜|镜头/.test(text);
  const hasScript = /剧本/.test(text);
  const hasAsset = /资产|人物|角色|场景|道具/.test(text);
  return hasDelete && hasChapter && !hasShot && !hasScript && !hasAsset;
}

function looksLikeDeleteShot(text: string): boolean {
  const hasDelete = /删除|删掉|去掉|移除/.test(text);
  const hasShot = /分镜|镜头/.test(text);
  return hasDelete && hasShot;
}

function looksLikeDeleteScript(text: string): boolean {
  const hasDelete = /删除|删掉|去掉|移除/.test(text);
  return hasDelete && /剧本/.test(text) && !/分镜|镜头/.test(text);
}

function looksLikeDeleteAsset(text: string): boolean {
  const hasDelete = /删除|删掉|去掉|移除/.test(text);
  return hasDelete && /资产|人物|角色|场景|道具/.test(text) && !/分镜|镜头|剧本/.test(text);
}

function looksLikeClearOrOverwriteSource(text: string): boolean {
  if (/清空|清除|抹掉/.test(text) && /原文|小说|全部章节/.test(text)) return true;
  if (/覆盖|重写整|整本重写|重新写一本/.test(text) && /原文|小说/.test(text)) return true;
  return false;
}

function detectDangerKind(text: string): DangerKind | null {
  if (looksLikeDeleteChapter(text)) return 'delete_chapter';
  if (looksLikeDeleteShot(text)) return 'delete_shots';
  if (looksLikeDeleteScript(text)) return 'delete_scripts';
  if (looksLikeDeleteAsset(text)) return 'delete_asset';
  if (looksLikeClearOrOverwriteSource(text)) return 'clear_source';
  return null;
}

function confirmTitle(kind: DangerKind | string | undefined): string {
  switch (kind) {
    case 'delete_chapter':
      return '确认删除章节？';
    case 'delete_shots':
      return '确认删除分镜？';
    case 'delete_scripts':
      return '确认删除剧本？';
    case 'delete_asset':
      return '确认删除资产？';
    case 'overwrite_source':
    case 'clear_source':
      return '确认覆盖原文？';
    default:
      return '确认执行危险操作？';
  }
}

function confirmDanger(opts: {
  kind?: string;
  summary?: string;
  fallbackText?: string;
}): Promise<boolean> {
  const content =
    opts.summary?.trim() ||
    (opts.fallbackText
      ? `将按「${opts.fallbackText}」执行。删除/覆盖前会自动保存快照，可在版本历史回滚。`
      : '此操作可能删除或覆盖数据，删除前会自动保存快照。');
  return new Promise((resolve) => {
    Modal.confirm({
      title: confirmTitle(opts.kind),
      content,
      okText: '确认执行',
      okType: 'danger',
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
}

function toastForResult(result: AssistantMessage) {
  const note =
    result.appliedActions && result.appliedActions.length > 0 && result.content?.includes('【本次变更】')
      ? result.content.split('【本次变更】').pop()?.trim()
      : undefined;
  if (result.sourceUpdated) {
    message.success(note ? `原文已更新：${note}` : '原文已更新');
  }
  if (result.shotsUpdated) {
    message.success(note ? `分镜已更新：${note}` : '分镜提示词已更新');
  }
  if (result.scriptsUpdated) {
    message.success(note ? `剧本已更新：${note}` : '剧本已更新');
  }
  if (result.assetsUpdated) {
    message.success(note ? `资产已更新：${note}` : '资产已更新');
  }
}

export function useAssistantChat(
  projectId: number,
  onProjectChanged?: () => void,
  onSourceStreaming?: (content: string) => void,
  onShotsStreaming?: (event: ShotsStreamEvent) => void,
  onAssistantStatus?: (text: string) => void,
  focus?: AssistantFocus,
  onScriptsStreaming?: (event: ScriptsStreamEvent) => void,
) {
  const [messages, setMessages] = useState<AssistantMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [statusText, setStatusText] = useState('');
  const abortRef = useRef<AbortController | null>(null);
  const focusRef = useRef(focus);
  focusRef.current = focus;

  const refreshHistory = useCallback(async () => {
    const list = await assistantApi.history(projectId);
    setMessages(list);
  }, [projectId]);

  useEffect(() => {
    refreshHistory().catch(() => undefined);
  }, [refreshHistory]);

  const send = useCallback(
    async (text: string) => {
      const content = text.trim();
      if (!content || loading) return;

      const danger = detectDangerKind(content);
      let confirmed = false;
      if (danger) {
        const ok = await confirmDanger({ kind: danger, fallbackText: content });
        if (!ok) return;
        confirmed = true;
      }

      const now = new Date().toISOString().slice(0, 19).replace('T', ' ');
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setLoading(true);
      setStatusText('连接中…');
      setInput('');

      let resumeConfirm = false;
      let wroteSource = false;
      let streamedShots = false;

      try {
        // 最多两轮：未确认 → 弹窗 → confirmed 重试
        for (let round = 0; round < 2; round++) {
          const userMsg: AssistantMessage = {
            id: Date.now() + round,
            role: 'user',
            content,
            createdAt: now,
          };
          const streamingMsg: AssistantMessage = {
            id: STREAMING_ID,
            role: 'assistant',
            content: '',
            createdAt: now,
          };

          setMessages((prev) =>
            resumeConfirm
              ? [...prev.filter((m) => m.id !== STREAMING_ID), streamingMsg]
              : [...prev.filter((m) => m.id !== STREAMING_ID), userMsg, streamingMsg],
          );

          wroteSource = false;
          streamedShots = false;
          const currentFocus = focusRef.current;

          const result = await assistantApi.chatStream(
            projectId,
            {
              message: content,
              focus: currentFocus?.focus ?? 'global',
              focusId: currentFocus?.focusId,
              selectedShotIds: currentFocus?.selectedShotIds,
              confirmed: confirmed || undefined,
              resumeConfirm: resumeConfirm || undefined,
            },
            {
              onStatus: (s) => {
                setStatusText(s);
                onAssistantStatus?.(s);
                setMessages((prev) =>
                  prev.map((m) =>
                    m.id === STREAMING_ID && !m.content.startsWith('已')
                      ? { ...m, content: s }
                      : m,
                  ),
                );
              },
              onShots: (event) => {
                streamedShots = true;
                onShotsStreaming?.(event);
              },
              onScripts: (event) => {
                onScriptsStreaming?.(event);
              },
              onSource: (sourceContent) => {
                wroteSource = true;
                onSourceStreaming?.(sourceContent);
              },
              onToken: (token) => {
                setStatusText('');
                setMessages((prev) =>
                  prev.map((m) =>
                    m.id === STREAMING_ID
                      ? {
                          ...m,
                          content: wroteSource ? token : m.content + token,
                        }
                      : m,
                  ),
                );
              },
              onDone: (done) => {
                setMessages((prev) =>
                  prev.map((m) =>
                    m.id === STREAMING_ID
                      ? {
                          ...done,
                          content: done.content || m.content,
                        }
                      : m,
                  ),
                );
              },
            },
            controller.signal,
          );

          if (!result) {
            setMessages((prev) =>
              prev.map((m) =>
                m.id === STREAMING_ID
                  ? { ...m, id: Date.now(), content: m.content || '（已完成）' }
                  : m,
              ),
            );
            break;
          }

          if (result.needsConfirm && !confirmed) {
            const ok = await confirmDanger({
              kind: result.confirmKind,
              summary: result.confirmSummary,
              fallbackText: content,
            });
            if (!ok) {
              message.info('已取消操作');
              break;
            }
            confirmed = true;
            resumeConfirm = true;
            continue;
          }

          if (
            result.sourceUpdated ||
            result.shotsUpdated ||
            result.scriptsUpdated ||
            result.assetsUpdated
          ) {
            toastForResult(result);
            if (result.sourceUpdated || wroteSource) onProjectChanged?.();
            if (result.shotsUpdated && !streamedShots) onProjectChanged?.();
            // 剧本已流式追加时仍要 loadAll，以带上资产与分镜占位
            if (result.scriptsUpdated || result.assetsUpdated) onProjectChanged?.();
          } else if (wroteSource) {
            message.success('原文已更新');
            onProjectChanged?.();
          }
          break;
        }

        await refreshHistory();
      } catch (e) {
        if (isAbortError(e)) {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === STREAMING_ID
                ? {
                    ...m,
                    id: Date.now(),
                    content: m.content ? `${m.content}\n\n（已停止）` : '（已停止）',
                  }
                : m,
            ),
          );
          message.info('已停止生成');
          await refreshHistory().catch(() => undefined);
          onProjectChanged?.();
          return;
        }
        setMessages((prev) =>
          prev.map((m) =>
            m.id === STREAMING_ID
              ? {
                  ...m,
                  id: Date.now(),
                  content: m.content || (e instanceof Error ? e.message : '发送失败'),
                }
              : m,
          ),
        );
        message.error(e instanceof Error ? e.message : '发送失败');
      } finally {
        if (abortRef.current === controller) {
          abortRef.current = null;
        }
        setLoading(false);
        setStatusText('');
        onAssistantStatus?.('');
      }
    },
    [
      projectId,
      loading,
      refreshHistory,
      onProjectChanged,
      onSourceStreaming,
      onShotsStreaming,
      onScriptsStreaming,
      onAssistantStatus,
    ],
  );

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  useEffect(() => () => abortRef.current?.abort(), []);

  return {
    messages,
    input,
    setInput,
    loading,
    statusText,
    send,
    stop,
    refreshHistory,
  };
}
