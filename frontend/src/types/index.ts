export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface ElementStyle {
  prefix?: string;
  suffix?: string;
}

export interface ElementStyles {
  character?: ElementStyle;
  scene?: ElementStyle;
  prop?: ElementStyle;
}

/** 分镜/作品视频参考方式 */
export type VideoRefMode = 'first_last' | 'single' | 'none';

/** 视频/参考图画幅比例 */
export type AspectRatio = '16:9' | '9:16' | '1:1' | '4:3' | '3:4';

export interface Project {
  id: number;
  title: string;
  stylePreset: string;
  /** 人物/场景/道具生图前缀后缀（elementStyle） */
  elementStyles?: ElementStyles;
  /** 作品默认参考方式：first_last | single | none（旧值 storyboard 视为 single） */
  refPromptMode?: VideoRefMode | 'storyboard' | string;
  /** 视频画幅：16:9 | 9:16 | 1:1 | 4:3 | 3:4 */
  videoAspectRatio?: AspectRatio;
  /** 分镜参考图画幅 */
  refImageAspectRatio?: AspectRatio;
  /** 人物/场景/道具资产图画幅 */
  assetImageAspectRatio?: AspectRatio;
  /** 风格参考文字（；生图时前置，不改分镜原文） */
  styleReferenceNote?: string;
  createdAt: string;
  updatedAt: string;
  scriptCount: number;
  shotCount: number;
  readOnly?: boolean;
}

export interface ScriptEntry {
  id: number;
  time: string;
  location: string;
  characters: string[];
  action: string;
  dialogue: string;
  shot: string;
  orderIndex: number;
}

export interface Asset {
  id: number;
  type: 'character' | 'scene' | 'prop';
  name: string;
  description: string;
  imagePrompt: string;
  referenceImageFile?: string;
  referenceImageUrl?: string;
}

export interface ReferencePrompt {
  id: number;
  type: 'first_frame' | 'last_frame' | 'other';
  prompt: string;
  note: string;
  orderIndex: number;
  referenceImageFile?: string;
  referenceImageUrl?: string;
}

export interface Shot {
  id: number;
  scriptId: number;
  orderIndex: number;
  videoPrompt: string;
  /** 分镜覆盖；空/未设则继承作品默认 */
  videoRefMode?: VideoRefMode | string | null;
  linkedAssetIds: number[];
  referencePrompts: ReferencePrompt[];
}

export interface ShotVideo {
  id: number;
  shotId: number;
  status: 'generating' | 'ready' | 'failed' | string;
  prompt?: string;
  remoteFile?: string;
  fileUrl?: string;
  errorMessage?: string;
  elapsedSec?: number;
  frames?: number;
  width?: number;
  height?: number;
  createdAt?: string;
}

export interface ProjectSnapshot {
  id: number;
  kind: string;
  label: string;
  createdAt: string;
}

export interface AiModelConfig {
  id: number;
  provider: string;
  modelName: string;
  baseUrl: string;
  configJson: string;
  active: boolean;
}

export interface AiConfigForm {
  provider: string;
  baseUrl: string;
  modelName: string;
  apiKey?: string;
  temperature: number;
  maxTokens: number;
}

export interface AiEndpointStatus {
  baseUrl: string;
  model?: string;
  apiMode: 'openai' | 'ollama' | 'status-page';
  online: boolean;
  modelAvailable: boolean;
  active: boolean;
}

export interface AssistantBrainStatus {
  provider: string;
  enabled: boolean;
  available: boolean;
  bin?: string;
  model?: string;
  mode?: string;
}

export interface AiHealthStatus {
  online: boolean;
  modelAvailable: boolean;
  baseUrl?: string;
  model?: string;
  activeBaseUrl?: string;
  activeModel?: string;
  provider?: string;
  temperature?: number;
  maxTokens?: number;
  personalOverride?: boolean;
  endpoints?: AiEndpointStatus[];
  assistant?: AssistantBrainStatus;
}

export type FeedbackType = 'bug' | 'feature' | 'improvement' | 'other';
export type FeedbackStatus = 'pending' | 'processing' | 'resolved' | 'closed';

export interface Feedback {
  id: number;
  type: FeedbackType;
  content: string;
  contact?: string;
  pageUrl?: string;
  status?: FeedbackStatus;
  adminReply?: string;
  handledAt?: string;
  createdAt: string;
}

export interface FeedbackForm {
  type: FeedbackType;
  content: string;
  contact?: string;
  pageUrl?: string;
}

export type AiHistoryType =
  | 'script_generate'
  | 'asset_extract'
  | 'shot_generate'
  | 'asset_regenerate'
  | 'shot_regenerate'
  | 'assistant_chat';

export interface AiHistoryItem {
  id: number;
  projectId: number;
  projectTitle: string;
  type: AiHistoryType;
  status: 'success' | 'failed';
  summary: string;
  detail?: string;
  createdAt: string;
}

