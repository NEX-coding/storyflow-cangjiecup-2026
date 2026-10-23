import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  DeleteOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { useAuth } from '../auth/AuthProvider';
import { AppLayout } from '../components/AppLayout';
import { aiConfigApi } from '../api/client';
import type { AiConfigForm, AiHealthStatus } from '../types';
import {
  DEFAULT_PERSONAL_AI,
  PERSONAL_AI_PRESETS,
  applyPersonalPreset,
  clearPersonalAiConfig,
  loadPersonalAiConfig,
  personalAiHeaders,
  savePersonalAiConfig,
  type PersonalAiConfig,
  type PersonalAiPreset,
} from '../lib/personalAi';
import '../styles/home.css';

const DEFAULTS: AiConfigForm = {
  provider: 'ollama',
  baseUrl: 'http://localhost:11434',
  modelName: 'qwen3.5:4b',
  apiKey: '',
  temperature: 0.3,
  maxTokens: 4096,
};

function endpointLabel(baseUrl: string, model?: string) {
  if (baseUrl.includes('model-api-mac')) {
    return model ? `本机 Mac · ${model}` : '本机 Mac';
  }
  if (baseUrl.includes('model-api')) {
    return model ? `备用 iMac · ${model}` : '备用 iMac（FRP 穿透）';
  }
  if (baseUrl.includes('deepseek')) {
    return model ? `DeepSeek · ${model}` : 'DeepSeek';
  }
  if (baseUrl.includes('openai.com')) {
    return model ? `OpenAI · ${model}` : 'OpenAI';
  }
  return model || '模型服务';
}

function parseConfigJson(json?: string): Pick<AiConfigForm, 'temperature' | 'maxTokens' | 'apiKey'> {
  try {
    const obj = JSON.parse(json || '{}') as Record<string, unknown>;
    return {
      temperature: typeof obj.temperature === 'number' ? obj.temperature : DEFAULTS.temperature,
      maxTokens: typeof obj.maxTokens === 'number' ? obj.maxTokens : DEFAULTS.maxTokens,
      apiKey: typeof obj.apiKey === 'string' ? obj.apiKey : '',
    };
  } catch {
    return { temperature: DEFAULTS.temperature, maxTokens: DEFAULTS.maxTokens, apiKey: '' };
  }
}

function StatusPanel({
  health,
  loading,
  onRefresh,
  showUrls = false,
  personalActive = false,
}: {
  health: AiHealthStatus | null;
  loading: boolean;
  onRefresh: () => void;
  showUrls?: boolean;
  personalActive?: boolean;
}) {
  return (
    <Card className="card-block" loading={loading}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
        <div style={{ flex: 1 }}>
          <Typography.Text strong style={{ color: 'var(--sf-text)' }}>
            服务状态
          </Typography.Text>
          <div style={{ marginTop: 10 }}>
            <Space wrap>
              {personalActive && <Tag color="blue">本机个人对接</Tag>}
              <Tag
                icon={health?.online ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
                color={health?.online ? 'success' : 'error'}
              >
                {health?.online ? '服务在线' : '服务离线'}
              </Tag>
              <Tag
                icon={health?.modelAvailable ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
                color={health?.modelAvailable ? 'success' : 'warning'}
              >
                {health?.modelAvailable ? '模型可用' : '模型未找到'}
              </Tag>
              {(health?.activeModel || health?.model) && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {showUrls
                    ? `${health.activeModel || health.model} @ ${health.activeBaseUrl || health.baseUrl}`
                    : endpointLabel(health.activeBaseUrl || health.baseUrl || '', health.activeModel || health.model)}
                </Typography.Text>
              )}
              {health?.assistant && (
                <Tag color={health.assistant.available ? 'purple' : 'default'}>
                  助手大脑 {health.assistant.provider}
                  {health.assistant.available ? ' · 增强推理可用' : ' · 回退文本模型'}
                </Tag>
              )}
            </Space>
            {health?.endpoints && health.endpoints.length > 0 && (
              <div style={{ marginTop: 12 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                  {personalActive
                    ? '当前使用你本机填写的服务'
                    : '端点列表（主端点不可用时自动切换备用）'}
                </Typography.Text>
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  {health.endpoints.map((ep) => (
                    <div key={ep.baseUrl} style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <Tag color={ep.active ? 'processing' : 'default'}>{ep.active ? '当前' : ep.apiMode}</Tag>
                      <Typography.Text style={{ fontSize: 12 }}>
                        {showUrls
                          ? `${ep.baseUrl}${ep.model ? ` (${ep.model})` : ''}`
                          : endpointLabel(ep.baseUrl, ep.model)}
                      </Typography.Text>
                      <Tag color={ep.online ? 'success' : 'error'}>{ep.online ? '在线' : '离线'}</Tag>
                      {ep.apiMode !== 'status-page' && (
                        <Tag color={ep.modelAvailable ? 'success' : 'warning'}>
                          {ep.modelAvailable ? '模型 OK' : '无模型'}
                        </Tag>
                      )}
                    </div>
                  ))}
                </Space>
              </div>
            )}
          </div>
        </div>
        <Button icon={<ReloadOutlined />} onClick={onRefresh} loading={loading}>
          刷新
        </Button>
      </div>
    </Card>
  );
}

export default function ApiPage() {
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN' && !user?.guest;
  const [adminForm] = Form.useForm<AiConfigForm>();
  const [personalForm] = Form.useForm<PersonalAiConfig>();
  const [health, setHealth] = useState<AiHealthStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [savingAdmin, setSavingAdmin] = useState(false);
  const [savingPersonal, setSavingPersonal] = useState(false);
  const [personal, setPersonal] = useState<PersonalAiConfig>(() => loadPersonalAiConfig());
  const adminProvider = Form.useWatch('provider', adminForm);
  const personalEnabled = Form.useWatch('enabled', personalForm);
  const personalPreset = Form.useWatch('preset', personalForm);
  const personalProvider = Form.useWatch('provider', personalForm);

  const load = async () => {
    setLoading(true);
    try {
      const personalCfg = loadPersonalAiConfig();
      setPersonal(personalCfg);
      personalForm.setFieldsValue(personalCfg);

      if (isAdmin) {
        const [config, status] = await Promise.all([aiConfigApi.get(), aiConfigApi.health()]);
        setHealth(status);
        if (config) {
          const extra = parseConfigJson(config.configJson);
          adminForm.setFieldsValue({
            provider: config.provider || 'ollama',
            baseUrl: config.baseUrl,
            modelName: config.modelName,
            apiKey: extra.apiKey,
            temperature: extra.temperature,
            maxTokens: extra.maxTokens,
          });
        } else {
          adminForm.setFieldsValue(DEFAULTS);
        }
      } else {
        const status = await aiConfigApi.status();
        setHealth(status);
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin]);

  const saveAdmin = async () => {
    const values = await adminForm.validateFields();
    setSavingAdmin(true);
    try {
      await aiConfigApi.save(values);
      message.success('平台 AI 配置已保存');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingAdmin(false);
    }
  };

  const onPresetChange = (preset: PersonalAiPreset) => {
    if (preset === 'platform') {
      personalForm.setFieldsValue({
        ...personalForm.getFieldsValue(),
        enabled: false,
        preset: 'platform',
      });
      return;
    }
    const next = applyPersonalPreset(preset, {
      ...DEFAULT_PERSONAL_AI,
      ...personalForm.getFieldsValue(),
      apiKey: personalForm.getFieldValue('apiKey') || '',
    });
    personalForm.setFieldsValue(next);
  };

  const savePersonal = async () => {
    const values = await personalForm.validateFields();
    if (values.enabled) {
      if (!values.baseUrl?.trim() || !values.modelName?.trim()) {
        message.error('请填写服务地址与模型名称');
        return;
      }
      if (values.provider === 'openai' && !values.apiKey?.trim() && values.preset !== 'ollama') {
        message.warning('云端接口通常需要 API Key，请确认已填写');
      }
    }
    setSavingPersonal(true);
    try {
      const next: PersonalAiConfig = {
        enabled: Boolean(values.enabled),
        preset: values.preset || 'custom',
        provider: values.provider === 'ollama' ? 'ollama' : 'openai',
        baseUrl: (values.baseUrl || '').trim(),
        modelName: (values.modelName || '').trim(),
        apiKey: (values.apiKey || '').trim(),
        temperature: values.temperature ?? 0.3,
        maxTokens: values.maxTokens ?? 4096,
      };
      savePersonalAiConfig(next);
      setPersonal(next);
      message.success(next.enabled ? '已保存到本浏览器，生成时将优先使用' : '已切换回平台默认服务');
      // 立刻用个人配置测连通性
      const status = await aiConfigApi.status();
      setHealth(status);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingPersonal(false);
    }
  };

  const clearPersonal = () => {
    clearPersonalAiConfig();
    const empty = { ...DEFAULT_PERSONAL_AI };
    setPersonal(empty);
    personalForm.setFieldsValue(empty);
    message.success('已清除本机配置');
    void load();
  };

  const personalActive =
    personal.enabled &&
    Boolean(personal.baseUrl.trim() && personal.modelName.trim()) &&
    Object.keys(personalAiHeaders(personal)).length > 0;

  return (
    <AppLayout>
      <div className="page-shell" style={{ maxWidth: 720 }}>
        <Typography.Title level={3} style={{ margin: '0 0 8px', color: 'var(--sf-text)' }}>
          AI 设置
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 24 }}>
          查看平台默认服务状态，也可在本浏览器对接 DeepSeek / OpenAI 等个人 Key。个人 Key
          只保存在你的电脑，不写入服务器，其他设备看不到。
        </Typography.Paragraph>

        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <StatusPanel
            health={health}
            loading={loading}
            onRefresh={() => void load()}
            showUrls={isAdmin || personalActive}
            personalActive={personalActive}
          />

          <Card className="card-block" title="我的 AI 对接（仅本机）">
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="隐私说明"
              description="API Key 与地址只保存在当前浏览器的 localStorage。关闭「启用」后仍使用平台默认服务；换电脑或清缓存后需重新填写。"
            />

            <Form form={personalForm} layout="vertical" initialValues={DEFAULT_PERSONAL_AI}>
              <Form.Item name="enabled" label="启用个人对接" valuePropName="checked">
                <Switch checkedChildren="启用" unCheckedChildren="关闭" />
              </Form.Item>

              <Form.Item name="preset" label="快捷预设">
                <Select
                  onChange={(v: PersonalAiPreset) => onPresetChange(v)}
                  options={[
                    { value: 'platform', label: '使用平台默认（不填个人 Key）' },
                    { value: 'deepseek', label: 'DeepSeek' },
                    { value: 'openai', label: 'OpenAI' },
                    { value: 'ollama', label: '本机 Ollama' },
                    { value: 'custom', label: '自定义 OpenAI 兼容' },
                  ]}
                />
              </Form.Item>

              {personalPreset && personalPreset !== 'platform' && (
                <Typography.Paragraph type="secondary" style={{ marginTop: -8, fontSize: 12 }}>
                  {PERSONAL_AI_PRESETS[personalPreset as Exclude<PersonalAiPreset, 'platform'>]?.hint}
                </Typography.Paragraph>
              )}

              <Form.Item
                name="provider"
                label="协议类型"
                rules={personalEnabled ? [{ required: true }] : []}
              >
                <Select
                  disabled={!personalEnabled}
                  options={[
                    { value: 'openai', label: 'OpenAI 兼容（DeepSeek / 云端）' },
                    { value: 'ollama', label: 'Ollama 原生' },
                  ]}
                />
              </Form.Item>

              <Form.Item
                name="baseUrl"
                label="服务地址"
                rules={personalEnabled ? [{ required: true, message: '请输入服务地址' }] : []}
                extra={
                  personalProvider === 'ollama'
                    ? '例如 http://localhost:11434'
                    : 'DeepSeek：https://api.deepseek.com/v1'
                }
              >
                <Input disabled={!personalEnabled} placeholder="https://api.deepseek.com/v1" />
              </Form.Item>

              <Form.Item
                name="modelName"
                label="模型名称"
                rules={personalEnabled ? [{ required: true, message: '请输入模型名称' }] : []}
                extra={
                  personalPreset === 'deepseek'
                    ? '常用 deepseek-chat / deepseek-reasoner'
                    : personalProvider === 'ollama'
                      ? '需先 ollama pull 对应模型'
                      : '例如 gpt-4o-mini'
                }
              >
                <Input disabled={!personalEnabled} placeholder="deepseek-chat" />
              </Form.Item>

              <Form.Item
                name="apiKey"
                label="API Key"
                extra="仅保存在本浏览器，不会写入服务器数据库"
              >
                <Input.Password
                  disabled={!personalEnabled}
                  placeholder="sk-..."
                  visibilityToggle
                  autoComplete="off"
                />
              </Form.Item>

              <Form.Item name="temperature" label="Temperature">
                <InputNumber
                  disabled={!personalEnabled}
                  min={0}
                  max={1}
                  step={0.1}
                  style={{ width: '100%' }}
                />
              </Form.Item>

              <Form.Item name="maxTokens" label="Max Tokens">
                <InputNumber
                  disabled={!personalEnabled}
                  min={512}
                  max={16384}
                  step={256}
                  style={{ width: '100%' }}
                />
              </Form.Item>

              <Space wrap>
                <Button
                  type="primary"
                  icon={<SaveOutlined />}
                  onClick={() => void savePersonal()}
                  loading={savingPersonal}
                >
                  保存到本机
                </Button>
                <Button icon={<DeleteOutlined />} onClick={clearPersonal}>
                  清除本机配置
                </Button>
              </Space>
            </Form>
          </Card>

          {isAdmin && (
            <Card className="card-block" title="平台默认配置（管理员）">
              <Form form={adminForm} layout="vertical" initialValues={DEFAULTS}>
                <Form.Item name="provider" label="服务类型" rules={[{ required: true }]}>
                  <Select
                    options={[
                      { value: 'ollama', label: 'Ollama（本地）' },
                      { value: 'openai', label: 'OpenAI 兼容接口' },
                    ]}
                  />
                </Form.Item>

                <Form.Item
                  name="baseUrl"
                  label="服务地址"
                  rules={[{ required: true, message: '请输入服务地址' }]}
                  extra={
                    adminProvider === 'ollama'
                      ? 'Ollama 默认 http://localhost:11434'
                      : '例如 https://api.openai.com/v1'
                  }
                >
                  <Input placeholder="http://localhost:11434" />
                </Form.Item>

                <Form.Item
                  name="modelName"
                  label="模型名称"
                  rules={[{ required: true, message: '请输入模型名称' }]}
                  extra={
                    adminProvider === 'ollama'
                      ? '推荐 qwen3.5:4b，需先执行 ollama pull'
                      : '例如 gpt-4o-mini'
                  }
                >
                  <Input placeholder="qwen3.5:4b" />
                </Form.Item>

                <Form.Item
                  name="apiKey"
                  label="API Key"
                  extra={
                    adminProvider === 'ollama'
                      ? '本地 Ollama 一般留空；若服务启用了鉴权可填写'
                      : '云端接口必填，保存在服务器（平台默认）'
                  }
                >
                  <Input.Password placeholder="sk-..." visibilityToggle />
                </Form.Item>

                <Form.Item name="temperature" label="Temperature">
                  <InputNumber min={0} max={1} step={0.1} style={{ width: '100%' }} />
                </Form.Item>

                <Form.Item name="maxTokens" label="Max Tokens">
                  <InputNumber min={512} max={8192} step={256} style={{ width: '100%' }} />
                </Form.Item>

                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message="平台配置"
                  description="此处为全站默认服务，所有未启用个人对接的用户都会使用。用户个人 Key 不会覆盖写入这里。"
                />

                <Button type="primary" onClick={() => void saveAdmin()} loading={savingAdmin}>
                  保存平台配置
                </Button>
              </Form>
            </Card>
          )}
        </Space>
      </div>
    </AppLayout>
  );
}
