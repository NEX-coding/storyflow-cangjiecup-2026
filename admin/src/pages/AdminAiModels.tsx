import { useEffect, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Row,
  Space,
  Tag,
  Typography,
  message,
} from 'antd';
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { adminApi } from '../api/client';
import type { AiEndpointStatus, AiHealth, MediaHealth, MediaModelConfig } from '../types';

function parseConfigJson(raw?: string): { temperature: number; maxTokens: number } {
  try {
    if (!raw) return { temperature: 0.3, maxTokens: 4096 };
    const parsed = JSON.parse(raw) as { temperature?: number; maxTokens?: number };
    return {
      temperature: typeof parsed.temperature === 'number' ? parsed.temperature : 0.3,
      maxTokens: typeof parsed.maxTokens === 'number' ? parsed.maxTokens : 4096,
    };
  } catch {
    return { temperature: 0.3, maxTokens: 4096 };
  }
}

function normalizeEndpoints(endpoints: AiHealth['endpoints']): AiEndpointStatus[] {
  if (!endpoints) return [];
  if (Array.isArray(endpoints)) return endpoints;
  return Object.entries(endpoints).map(([baseUrl, status]) => ({
    baseUrl,
    online: status === true || status === 'ok',
    modelAvailable: status === true || status === 'ok',
  }));
}

function statusColor(online?: boolean, status?: string) {
  if (status === 'configured-unverified') return 'warning';
  if (online) return status === 'auth-required' ? 'warning' : 'success';
  return 'error';
}

function statusLabel(online?: boolean, status?: string) {
  if (status === 'channel-unavailable') return '上游视频权限未开放';
  if (status === 'missing-key') return '未配置密钥';
  if (status === 'configured-unverified') return '已配置，待实测';
  if (status === 'auth-required') return '可达（需鉴权）';
  if (status === 'unreachable') return '不可达';
  if (status === 'missing-url') return '未配置';
  if (status === 'model-missing') return '端点在线但缺模型';
  if (online) return '在线';
  return '离线';
}

export default function AdminAiModels() {
  const [llmForm] = Form.useForm();
  const [mediaForm] = Form.useForm();
  const [health, setHealth] = useState<AiHealth | null>(null);
  const [mediaHealth, setMediaHealth] = useState<MediaHealth | null>(null);
  const [mediaMeta, setMediaMeta] = useState<MediaModelConfig | null>(null);
  const [loading, setLoading] = useState(false);
  const [savingLlm, setSavingLlm] = useState(false);
  const [savingMedia, setSavingMedia] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const [config, llmHealth, mediaConfig, mediaStatus] = await Promise.all([
        adminApi.getAiConfig(),
        adminApi.aiHealth(),
        adminApi.getMediaConfig(),
        adminApi.mediaHealth(),
      ]);
      if (config) {
        const extra = parseConfigJson(config.configJson);
        llmForm.setFieldsValue({
          baseUrl: config.baseUrl,
          modelName: config.modelName,
          temperature: extra.temperature,
          maxTokens: extra.maxTokens,
        });
      }
      mediaForm.setFieldsValue({
        imageModel: mediaConfig.imageModel || 'x/flux2-klein:4b',
        ollamaImageBaseUrl: mediaConfig.ollamaImageBaseUrl || 'http://127.0.0.1:11434',
        apiToken: mediaConfig.apiTokenMasked || '',
        imageDefaultWidth: mediaConfig.imageDefaultWidth,
        imageDefaultHeight: mediaConfig.imageDefaultHeight,
        imageDefaultSteps: mediaConfig.imageDefaultSteps,
        defaultFrames: mediaConfig.defaultFrames,
        defaultSteps: mediaConfig.defaultSteps,
        defaultWidth: mediaConfig.defaultWidth,
        defaultHeight: mediaConfig.defaultHeight,
      });
      setHealth(llmHealth);
      setMediaHealth(mediaStatus);
      setMediaMeta(mediaConfig);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const saveLlm = async () => {
    const values = await llmForm.validateFields();
    setSavingLlm(true);
    try {
      await adminApi.saveAiConfig(values);
      message.success('文本模型配置已保存');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingLlm(false);
    }
  };

  const saveMedia = async () => {
    const values = await mediaForm.validateFields();
    setSavingMedia(true);
    try {
      const payload = { ...values } as Record<string, unknown>;
      const token = String(values.apiToken || '');
      if (!token || token.includes('…') || /^\*+$/.test(token)) {
        delete payload.apiToken;
      }
      await adminApi.saveMediaConfig(payload);
      message.success('媒体模型配置已保存（运行时生效，重启后回退环境变量）');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSavingMedia(false);
    }
  };

  const endpoints = normalizeEndpoints(health?.endpoints);

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h2>AI 模型</h2>
          <p>管理文本推理与生图服务地址与默认参数，并查看连通状态（视频生成已下线）。</p>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
          刷新状态
        </Button>
      </div>

      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card className="admin-card" title="总览状态" loading={loading && !health && !mediaHealth}>
          <Row gutter={[12, 12]}>
            <Col xs={24} md={8}>
              <div className="admin-status-row">
                <Typography.Text type="secondary">文本 LLM</Typography.Text>
                <Tag color={health?.online ? 'success' : 'error'}>
                  {health?.online ? '在线' : '离线'}
                </Tag>
                <Tag color={health?.modelAvailable ? 'success' : 'warning'}>
                  {health?.modelAvailable ? '模型可用' : '模型未找到'}
                </Tag>
              </div>
              <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                {health?.activeBaseUrl || '—'} · {health?.activeModel || '—'}
              </Typography.Paragraph>
            </Col>
            <Col xs={24} md={8}>
              <div className="admin-status-row">
                <Typography.Text type="secondary">生图</Typography.Text>
                <Tag color={statusColor(mediaHealth?.image?.online, mediaHealth?.image?.status)}>
                  {statusLabel(mediaHealth?.image?.online, mediaHealth?.image?.status)}
                </Tag>
              </div>
              <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                {mediaHealth?.image?.baseUrl || '—'}
              </Typography.Paragraph>
            </Col>
            <Col xs={24} md={8}>
              <div className="admin-status-row">
                <Typography.Text type="secondary">视频</Typography.Text>
                <Tag>已下线</Tag>
              </div>
              <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                仅保留历史成片回放
              </Typography.Paragraph>
            </Col>
          </Row>
          {mediaMeta && (
            <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
              Token：{mediaMeta.tokenConfigured ? mediaMeta.apiTokenMasked || '已配置' : '未配置'}
              {mediaMeta.storageDir ? ` · 落盘 ${mediaMeta.storageDir}` : ''}
            </Typography.Paragraph>
          )}
        </Card>

        <Card className="admin-card" title="文本模型（Ollama / OpenAI 兼容）">
          {endpoints.length > 0 && (
            <div className="admin-endpoint-list" style={{ marginBottom: 16 }}>
              {endpoints.map((ep) => (
                <Tag
                  key={ep.baseUrl}
                  color={ep.active ? 'processing' : ep.online ? 'success' : 'default'}
                >
                  {ep.active ? '当前 · ' : ''}
                  {ep.apiMode || 'api'} {ep.model || ''} @ {ep.baseUrl}
                  {ep.online ? ' ✓' : ' ✗'}
                </Tag>
              ))}
            </div>
          )}
          <Form form={llmForm} layout="vertical" style={{ maxWidth: 640 }}>
            <Form.Item
              name="baseUrl"
              label="服务地址"
              rules={[{ required: true }]}
              extra="Ollama 或 OpenAI 兼容接口地址"
            >
              <Input placeholder="http://localhost:11434" />
            </Form.Item>
            <Form.Item name="modelName" label="模型名称" rules={[{ required: true }]}>
              <Input placeholder="qwen3.5:4b" />
            </Form.Item>
            <Row gutter={12}>
              <Col span={12}>
                <Form.Item name="temperature" label="Temperature">
                  <InputNumber min={0} max={1} step={0.1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="maxTokens" label="Max Tokens">
                  <InputNumber min={512} max={8192} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
            <Button type="primary" icon={<SaveOutlined />} onClick={() => void saveLlm()} loading={savingLlm}>
              保存文本模型
            </Button>
          </Form>
        </Card>

        <Card
          className="admin-card"
          title="生图模型（Ollama FLUX.2 Klein）"
          extra={
            mediaHealth?.image && (
              <Tag color={statusColor(mediaHealth.image.online, mediaHealth.image.status)}>
                {statusLabel(mediaHealth.image.online, mediaHealth.image.status)}
                {mediaHealth.image.httpStatus ? ` · HTTP ${mediaHealth.image.httpStatus}` : ''}
              </Tag>
            )
          }
        >
          <Form form={mediaForm} layout="vertical" style={{ maxWidth: 720 }}>
            <Form.Item
              name="imageModel"
              label="Ollama 图片模型"
              rules={[{ required: true, message: '请填写图片模型名' }]}
              extra="需本机已 ollama pull，默认 x/flux2-klein:4b"
            >
              <Input placeholder="x/flux2-klein:4b" />
            </Form.Item>
            <Form.Item
              name="ollamaImageBaseUrl"
              label="生图 Ollama 地址"
              rules={[{ required: true, message: '请填写本机 Ollama 地址' }]}
              extra="与文本端点可分离；本机一般为 http://127.0.0.1:11434"
            >
              <Input placeholder="http://127.0.0.1:11434" />
            </Form.Item>
            <Row gutter={12}>
              <Col xs={24} sm={8}>
                <Form.Item name="imageDefaultWidth" label="默认宽度">
                  <InputNumber min={256} max={2048} step={64} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={8}>
                <Form.Item name="imageDefaultHeight" label="默认高度">
                  <InputNumber min={256} max={2048} step={64} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={8}>
                <Form.Item name="imageDefaultSteps" label="默认步数">
                  <InputNumber min={1} max={50} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              onClick={() => void saveMedia()}
              loading={savingMedia}
            >
              保存生图配置
            </Button>
          </Form>
        </Card>

      </Space>
    </div>
  );
}
