import axios from 'axios';
import type {
  ApiResponse,
  AiHealth,
  AiModelConfig,
  AdminFeedback,
  AdminFeedbackPage,
  AdminUser,
  AdminUserPage,
  DashboardStats,
  MediaHealth,
  MediaModelConfig,
  PromptTemplate,
} from '../types';
import { authHeaders, clearAuthSession, type AuthResult, type AuthUser } from '../lib/auth';

const apiBase = (import.meta.env.VITE_API_BASE as string | undefined) || '/api';

const client = axios.create({ baseURL: apiBase });

client.interceptors.request.use((config) => {
  const headers = authHeaders();
  Object.entries(headers).forEach(([key, value]) => {
    config.headers.set(key, value);
  });
  return config;
});

client.interceptors.response.use(
  (res) => {
    const body = res.data as ApiResponse<unknown>;
    if (body.code !== 200) {
      if (body.code === 401) {
        clearAuthSession();
      }
      return Promise.reject(new Error(body.message || '请求失败'));
    }
    return res;
  },
  (err) => {
    const code = err.response?.data?.code;
    if (code === 401 || err.response?.status === 401) {
      clearAuthSession();
    }
    return Promise.reject(new Error(err.response?.data?.message || err.message));
  },
);

export const authApi = {
  login: (username: string, password: string) =>
    client
      .post<ApiResponse<AuthResult>>('/admin/auth/login', { username, password })
      .then((r) => r.data.data),
  logout: () => client.post<ApiResponse<null>>('/admin/auth/logout').then((r) => r.data.data),
  me: () => client.get<ApiResponse<AuthUser>>('/admin/auth/me').then((r) => r.data.data),
};

export const adminApi = {
  dashboard: () =>
    client.get<ApiResponse<DashboardStats>>('/admin/dashboard').then((r) => r.data.data),

  listUsers: (params: {
    q?: string;
    role?: string;
    enabled?: boolean;
    page?: number;
    size?: number;
  }) =>
    client
      .get<ApiResponse<AdminUserPage>>('/admin/users', { params })
      .then((r) => r.data.data),

  getUser: (id: number) =>
    client.get<ApiResponse<AdminUser>>(`/admin/users/${id}`).then((r) => r.data.data),

  updateUserStatus: (id: number, enabled: boolean) =>
    client
      .put<ApiResponse<AdminUser>>(`/admin/users/${id}/status`, { enabled })
      .then((r) => r.data.data),

  listFeedbacks: (params: {
    q?: string;
    type?: string;
    status?: string;
    page?: number;
    size?: number;
  }) =>
    client
      .get<ApiResponse<AdminFeedbackPage>>('/admin/feedbacks', { params })
      .then((r) => r.data.data),

  handleFeedback: (id: number, data: { status: string; adminReply?: string }) =>
    client
      .put<ApiResponse<AdminFeedback>>(`/admin/feedbacks/${id}`, data)
      .then((r) => r.data.data),

  listTemplates: () =>
    client.get<ApiResponse<PromptTemplate[]>>('/admin/prompt-templates').then((r) => r.data.data),

  saveTemplate: (template: Partial<PromptTemplate>) =>
    template.id
      ? client
          .put<ApiResponse<PromptTemplate>>(`/admin/prompt-templates/${template.id}`, template)
          .then((r) => r.data.data)
      : client
          .post<ApiResponse<PromptTemplate>>('/admin/prompt-templates', template)
          .then((r) => r.data.data),

  getAiConfig: () =>
    client.get<ApiResponse<AiModelConfig>>('/admin/ai-models').then((r) => r.data.data),

  saveAiConfig: (data: {
    baseUrl: string;
    modelName: string;
    temperature: number;
    maxTokens: number;
  }) => client.put<ApiResponse<AiModelConfig>>('/admin/ai-models', data).then((r) => r.data.data),

  aiHealth: () =>
    client.get<ApiResponse<AiHealth>>('/admin/ai-models/health').then((r) => r.data.data),

  getMediaConfig: () =>
    client.get<ApiResponse<MediaModelConfig>>('/admin/media-models').then((r) => r.data.data),

  saveMediaConfig: (data: Partial<MediaModelConfig> & { apiToken?: string; geeknowApiKey?: string }) =>
    client.put<ApiResponse<MediaModelConfig>>('/admin/media-models', data).then((r) => r.data.data),

  mediaHealth: () =>
    client.get<ApiResponse<MediaHealth>>('/admin/media-models/health').then((r) => r.data.data),
};
