/**
 * AI 助手独立模块
 *
 * - 自有 API / 类型 / Hook / UI
 * - 通过 onProjectChanged 与工作台解耦通信
 * - 宿主页面只负责挂载与开关
 */
export { AssistantModule } from './components/AssistantModule';
export { AssistantPanel } from './components/AssistantPanel';
export { useAssistantChat } from './hooks/useAssistantChat';
export { assistantApi } from './api';
export type {
  AssistantMessage,
  AssistantChatRequest,
  AssistantEvent,
  AssistantModuleProps,
} from './types';
