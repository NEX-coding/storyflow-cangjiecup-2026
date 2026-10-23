export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface PromptTemplate {
  id: number;
  name: string;
  category: string;
  content: string;
  version: number;
  active: boolean;
}

export interface AiModelConfig {
  id: number;
  provider: string;
  modelName: string;
  baseUrl: string;
  configJson: string;
  active: boolean;
}

export interface AiEndpointStatus {
  baseUrl: string;
  model?: string;
  apiMode?: string;
  online?: boolean;
  modelAvailable?: boolean;
  active?: boolean;
}

export interface AiHealth {
  online: boolean;
  modelAvailable: boolean;
  activeBaseUrl?: string;
  activeModel?: string;
  baseUrl?: string;
  model?: string;
  endpoints?: AiEndpointStatus[] | Record<string, boolean | string>;
}

export interface MediaEndpointHealth {
  kind: string;
  baseUrl: string;
  online: boolean;
  status: string;
  httpStatus?: number;
  message?: string;
}

export interface MediaHealth {
  image: MediaEndpointHealth;
  video: MediaEndpointHealth;
  videoProvider?: string;
  tokenConfigured?: boolean;
}

export interface MediaModelConfig {
  /** @deprecated 生图已改 Ollama */
  imageBaseUrl?: string;
  imageModel?: string;
  ollamaImageBaseUrl?: string;
  h3BaseUrl: string;
  mlxBaseUrl?: string;
  videoProvider: string;
  geeknowBaseUrl?: string;
  geeknowApiKeyMasked?: string;
  geeknowKeyConfigured?: boolean;
  apiTokenMasked?: string;
  tokenConfigured?: boolean;
  storageDir?: string;
  archiveBaseUrl?: string;
  publicAssetBaseUrl?: string;
  defaultFrames: number;
  defaultSteps: number;
  defaultWidth: number;
  defaultHeight: number;
  imageDefaultWidth: number;
  imageDefaultHeight: number;
  imageDefaultSteps: number;
  runtimeOnly?: boolean;
}

export interface AdminUser {
  id: number;
  email: string;
  nickname: string;
  role: 'USER' | 'ADMIN';
  enabled: boolean;
  createdAt: string;
  projectCount: number;
  aiCallCount: number;
  systemAccount: boolean;
}

export interface AdminUserPage {
  items: AdminUser[];
  total: number;
  page: number;
  size: number;
}

export interface DashboardStats {
  userCount?: number;
  activeUserCount?: number;
  projectCount?: number;
  aiCallCount?: number;
  todayAiCallCount?: number;
  templateCount?: number;
  feedbackCount?: number;
  pendingFeedbackCount?: number;
}

export type FeedbackType = 'bug' | 'feature' | 'improvement' | 'other';
export type FeedbackStatus = 'pending' | 'processing' | 'resolved' | 'closed';

export interface AdminFeedback {
  id: number;
  userId: number;
  userEmail?: string;
  userNickname?: string;
  type: FeedbackType | string;
  content: string;
  contact?: string;
  pageUrl?: string;
  status: FeedbackStatus | string;
  adminReply?: string;
  handledAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface AdminFeedbackPage {
  items: AdminFeedback[];
  total: number;
  page: number;
  size: number;
}
