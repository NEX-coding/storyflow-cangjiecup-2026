import { Button, Input, Typography } from 'antd';
import { CloseOutlined, PauseOutlined, SendOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import { useAssistantChat } from '../hooks/useAssistantChat';
import type { AssistantFocus, ScriptsStreamEvent, ShotsStreamEvent } from '../types';
import { AssistantMessageContent } from './AssistantMessageContent';
import '../assistant.css';

const { TextArea } = Input;

interface Props {
  projectId: number;
  focus?: AssistantFocus;
  onClose?: () => void;
  onProjectChanged?: () => void;
  onSourceStreaming?: (content: string) => void;
  onShotsStreaming?: (event: ShotsStreamEvent) => void;
  onScriptsStreaming?: (event: ScriptsStreamEvent) => void;
  onAssistantStatus?: (text: string) => void;
}

function formatThoughtDuration(ms: number): string {
  const sec = Math.max(1, Math.round(ms / 1000));
  return `${sec} 秒`;
}

/** 助手对话面板布局 */
export function AssistantPanel({
  projectId,
  focus,
  onClose,
  onProjectChanged,
  onSourceStreaming,
  onShotsStreaming,
  onScriptsStreaming,
  onAssistantStatus,
}: Props) {
  const { messages, input, setInput, loading, statusText, send, stop } = useAssistantChat(
    projectId,
    onProjectChanged,
    onSourceStreaming,
    onShotsStreaming,
    onAssistantStatus,
    focus,
    onScriptsStreaming,
  );
  const listRef = useRef<HTMLDivElement>(null);
  const [thoughtStartedAt, setThoughtStartedAt] = useState<number | null>(null);
  const [thoughtElapsed, setThoughtElapsed] = useState('');

  useEffect(() => {
    const el = listRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages, loading, statusText, thoughtElapsed]);

  useEffect(() => {
    if (loading) {
      setThoughtStartedAt(Date.now());
      return;
    }
    setThoughtStartedAt(null);
    setThoughtElapsed('');
  }, [loading]);

  useEffect(() => {
    if (!loading || thoughtStartedAt == null) return;

    const tick = () => {
      setThoughtElapsed(formatThoughtDuration(Date.now() - thoughtStartedAt));
    };
    tick();
    const id = window.setInterval(tick, 500);
    return () => window.clearInterval(id);
  }, [loading, thoughtStartedAt]);

  const handleSend = () => {
    if (!input.trim() || loading) return;
    send(input);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const streamingMsg = messages.find((m) => m.id === -1);
  const historyMessages = messages.filter((m) => m.id !== -1);
  const selectedCount = focus?.selectedShotIds?.length ?? 0;
  const focusAsset = focus?.focus === 'asset';

  return (
    <div className="assistant-panel">
      <div className="assistant-panel__header">
        <div className="assistant-panel__title-wrap">
          <Typography.Text className="assistant-panel__title">StoryFlow 助手</Typography.Text>
          <Typography.Text className="assistant-panel__subtitle">创作工具链 · 智能问答</Typography.Text>
        </div>
        {onClose && (
          <Button
            type="text"
            size="small"
            className="assistant-panel__close"
            icon={<CloseOutlined />}
            aria-label="关闭 AI 助手"
            onClick={onClose}
          />
        )}
      </div>

      <div className="assistant-panel__messages" ref={listRef}>
        {historyMessages.length === 0 && !loading && (
          <p className="assistant-panel__empty">
            可问答、给创作建议；写原文 / 生成剧本 / 提取资产 / 分镜提示词由工具链直接执行。
            可说「重写第1条剧本」「删除资产林浅」「重新生成林浅的生图提示词」。
            {focusAsset
              ? ' 当前已选中资产，可直接说「重新生成生图提示词」或「重写资产」。'
              : selectedCount > 0
                ? ` 当前已选 ${selectedCount} 个分镜，可说「重写这些剧本/提示词」。`
                : ''}
          </p>
        )}

        {historyMessages.map((m) =>
          m.role === 'user' ? (
            <div key={m.id} className="assistant-user-msg">
              <div className="assistant-user-msg__pill">{m.content}</div>
            </div>
          ) : (
            <div key={m.id} className="assistant-assistant-msg">
              <AssistantMessageContent content={m.content} />
            </div>
          ),
        )}

        {loading && (
          <div className="assistant-assistant-msg">
            {statusText ? (
              <div className="assistant-thinking">
                <span className="assistant-thinking__label">
                  {thoughtElapsed ? `已思考 ${thoughtElapsed}` : '思考中'}
                </span>
                <div className="assistant-thinking__text">{statusText}</div>
              </div>
            ) : (
              <>
                {thoughtElapsed && (
                  <span className="assistant-thinking__label">已思考 {thoughtElapsed}</span>
                )}
                <AssistantMessageContent content={streamingMsg?.content ?? ''} streaming />
              </>
            )}
          </div>
        )}
      </div>

      <div className="assistant-panel__footer">
        {(selectedCount > 0 || focusAsset) && (
          <div className="assistant-focus-hint">
            {focusAsset
              ? '已关联选中资产'
              : `已关联选中分镜 ${selectedCount} 个`}
          </div>
        )}
        <div className="assistant-quick-actions">
          {[
            { label: '生成剧本', message: '请根据当前原文生成剧本' },
            { label: '提取资产', message: '请从当前剧本提取资产' },
            { label: '全部分镜提示词', message: '请生成全部分镜提示词' },
          ].map((item) => (
            <button
              key={item.label}
              type="button"
              className="assistant-quick-action"
              disabled={loading}
              onClick={() => send(item.message)}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="assistant-composer">
          <TextArea
            className="assistant-composer__input"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="例如：重新写第一章 / 重写选中分镜提示词"
            disabled={loading}
            autoSize={{ minRows: 1, maxRows: 6 }}
          />
          <div className="assistant-composer__actions">
            <span className="assistant-composer__hint">
              {loading ? '生成中，可随时停止' : 'Enter 发送 · Shift+Enter 换行'}
            </span>
            {loading ? (
              <Button
                danger
                size="small"
                className="assistant-composer__stop"
                icon={<PauseOutlined />}
                onClick={stop}
              >
                停止
              </Button>
            ) : (
              <Button
                type="primary"
                size="small"
                className="assistant-composer__send"
                icon={<SendOutlined />}
                disabled={!input.trim()}
                onClick={handleSend}
              >
                发送
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
