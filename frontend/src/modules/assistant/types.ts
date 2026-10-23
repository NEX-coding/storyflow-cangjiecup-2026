import type { ScriptEntry } from '../../types';

export interface AssistantMessage {
  id: number;
  role: string;
  content: string;
  createdAt: string;
  sourceUpdated?: boolean;
  shotsUpdated?: boolean;
  scriptsUpdated?: boolean;
  assetsUpdated?: boolean;
  appliedActions?: string[];
  /** 后端要求二次确认危险操作 */
  needsConfirm?: boolean;
  confirmKind?: string;
  confirmSummary?: string;
}

export interface AssistantChatRequest {
  message: string;
  focus?: 'global' | 'script' | 'asset' | 'shot';
  focusId?: number;
  selectedShotIds?: number[];
  /** 危险操作已在前端弹窗确认 */
  confirmed?: boolean;
  /** 确认重试：不重复落库用户消息 */
  resumeConfirm?: boolean;
}

export interface ShotsStreamEvent {
  phase: 'placeholders' | 'streaming' | 'updated';
  shotNumber?: number;
  videoPrompt?: string;
}

export interface ScriptsStreamEvent {
  phase: 'cleared' | 'append' | 'updated';
  items?: ScriptEntry[];
}

export interface AssistantStreamHandlers {
  onToken?: (text: string) => void;
  onStatus?: (text: string) => void;
  /** 写原文时推送完整原文快照，宿主应写入左侧原文框 */
  onSource?: (content: string) => void;
  /** 分镜提示词生成过程中推送，宿主应刷新中栏分镜列表 */
  onShots?: (event: ShotsStreamEvent) => void;
  /** 剧本生成过程中推送，宿主应实时更新中栏剧本行 */
  onScripts?: (event: ScriptsStreamEvent) => void;
  onDone?: (msg: AssistantMessage) => void;
}

export interface AssistantEvent {
  type: 'source_updated' | 'actions_applied';
  actions?: string[];
}

export interface AssistantFocus {
  focus?: AssistantChatRequest['focus'];
  focusId?: number;
  selectedShotIds?: number[];
}

export interface AssistantModuleProps {
  projectId: number;
  open?: boolean;
  width?: number;
  onOpenChange?: (open: boolean) => void;
  /** 工作台当前选中上下文，发给助手提升定向修改准确度 */
  focus?: AssistantFocus;
  /** 助手流式写入原文时，实时更新左侧原文框 */
  onSourceStreaming?: (content: string) => void;
  /** 助手修改了项目数据（如写入原文）时回调，由宿主刷新工作台 */
  onProjectChanged?: () => void;
  /** 分镜提示词流式更新时回调，由宿主刷新中栏 */
  onShotsStreaming?: (event: ShotsStreamEvent) => void;
  /** 剧本生成流式更新时回调，由宿主实时追加中栏剧本行 */
  onScriptsStreaming?: (event: ScriptsStreamEvent) => void;
  /** 助手状态文案变化（如「正在生成分镜 #2…」），用于中栏 loading 指示 */
  onAssistantStatus?: (text: string) => void;
}
