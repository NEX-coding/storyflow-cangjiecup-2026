export type PersonalAiPreset = 'platform' | 'deepseek' | 'openai' | 'ollama' | 'custom';

export interface PersonalAiConfig {
  /** 是否启用本机个人对接（关闭则走平台默认服务） */
  enabled: boolean;
  preset: PersonalAiPreset;
  provider: 'ollama' | 'openai';
  baseUrl: string;
  modelName: string;
  apiKey: string;
  temperature: number;
  maxTokens: number;
}

const STORAGE_KEY = 'storyflow-personal-ai';

export const PERSONAL_AI_PRESETS: Record<
  Exclude<PersonalAiPreset, 'platform'>,
  Omit<PersonalAiConfig, 'enabled' | 'apiKey'> & { label: string; hint: string }
> = {
  deepseek: {
    preset: 'deepseek',
    provider: 'openai',
    baseUrl: 'https://api.deepseek.com/v1',
    modelName: 'deepseek-chat',
    temperature: 0.3,
    maxTokens: 4096,
    label: 'DeepSeek',
    hint: '填写 DeepSeek API Key，仅保存在本浏览器',
  },
  openai: {
    preset: 'openai',
    provider: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    modelName: 'gpt-4o-mini',
    temperature: 0.3,
    maxTokens: 4096,
    label: 'OpenAI',
    hint: '填写 OpenAI API Key，仅保存在本浏览器',
  },
  ollama: {
    preset: 'ollama',
    provider: 'ollama',
    baseUrl: 'http://localhost:11434',
    modelName: 'qwen3.5:4b',
    temperature: 0.3,
    maxTokens: 4096,
    label: '本机 Ollama',
    hint: '连接你电脑上的 Ollama，一般无需 Key',
  },
  custom: {
    preset: 'custom',
    provider: 'openai',
    baseUrl: 'https://api.example.com/v1',
    modelName: 'your-model',
    temperature: 0.3,
    maxTokens: 4096,
    label: '自定义 OpenAI 兼容',
    hint: '任意 OpenAI 兼容接口（硅基流动、通义等）',
  },
};

export const DEFAULT_PERSONAL_AI: PersonalAiConfig = {
  enabled: false,
  preset: 'platform',
  provider: 'openai',
  baseUrl: '',
  modelName: '',
  apiKey: '',
  temperature: 0.3,
  maxTokens: 4096,
};

export function loadPersonalAiConfig(): PersonalAiConfig {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { ...DEFAULT_PERSONAL_AI };
    const parsed = JSON.parse(raw) as Partial<PersonalAiConfig>;
    return {
      enabled: Boolean(parsed.enabled),
      preset: (parsed.preset as PersonalAiPreset) || 'platform',
      provider: parsed.provider === 'ollama' ? 'ollama' : 'openai',
      baseUrl: typeof parsed.baseUrl === 'string' ? parsed.baseUrl : '',
      modelName: typeof parsed.modelName === 'string' ? parsed.modelName : '',
      apiKey: typeof parsed.apiKey === 'string' ? parsed.apiKey : '',
      temperature: typeof parsed.temperature === 'number' ? parsed.temperature : 0.3,
      maxTokens: typeof parsed.maxTokens === 'number' ? parsed.maxTokens : 4096,
    };
  } catch {
    return { ...DEFAULT_PERSONAL_AI };
  }
}

export function savePersonalAiConfig(config: PersonalAiConfig) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(config));
}

export function clearPersonalAiConfig() {
  localStorage.removeItem(STORAGE_KEY);
}

/** 仅在启用且填写完整时附加请求头；Key 不落服务端库 */
export function personalAiHeaders(config: PersonalAiConfig = loadPersonalAiConfig()): Record<string, string> {
  if (!config.enabled) return {};
  const baseUrl = config.baseUrl.trim();
  const modelName = config.modelName.trim();
  if (!baseUrl || !modelName) return {};

  const headers: Record<string, string> = {
    'X-StoryFlow-AI-Override': '1',
    'X-StoryFlow-AI-Base-Url': baseUrl,
    'X-StoryFlow-AI-Model': modelName,
    'X-StoryFlow-AI-Provider': config.provider,
    'X-StoryFlow-AI-Temperature': String(config.temperature ?? 0.3),
    'X-StoryFlow-AI-Max-Tokens': String(config.maxTokens ?? 4096),
  };
  const apiKey = config.apiKey.trim();
  if (apiKey) {
    headers['X-StoryFlow-AI-Api-Key'] = apiKey;
  }
  return headers;
}

export function applyPersonalPreset(
  preset: Exclude<PersonalAiPreset, 'platform'>,
  current: PersonalAiConfig,
): PersonalAiConfig {
  const base = PERSONAL_AI_PRESETS[preset];
  return {
    ...current,
    enabled: true,
    preset,
    provider: base.provider,
    baseUrl: base.baseUrl,
    modelName: base.modelName,
    temperature: base.temperature,
    maxTokens: base.maxTokens,
    // 切换预设时保留已填 Key（同类型服务常见）
    apiKey: current.apiKey,
  };
}
