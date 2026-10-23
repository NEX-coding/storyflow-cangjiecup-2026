import { useEffect, useState } from 'react';
import {
  ApiOutlined,
  ExportOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { Button, Card, Form, Select, Space, Switch, Typography, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { AppLayout } from '../components/AppLayout';
import {
  DEFAULT_USER_SETTINGS,
  STYLE_PRESETS,
  loadUserSettings,
  saveUserSettings,
  type UserSettings,
} from '../lib/userSettings';
import '../styles/home.css';

export default function SettingsPage() {
  const navigate = useNavigate();
  const [form] = Form.useForm<UserSettings>();
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    form.setFieldsValue(loadUserSettings());
  }, [form]);

  const reset = () => {
    form.setFieldsValue(DEFAULT_USER_SETTINGS);
    setDirty(true);
  };

  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      saveUserSettings(values);
      setDirty(false);
      message.success('设置已保存');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <AppLayout>
      <div className="page-shell" style={{ maxWidth: 720 }}>
        <Typography.Title level={3} style={{ margin: '0 0 8px', color: 'var(--sf-text)' }}>
          设置
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 24 }}>
          管理创作偏好与导出默认值。设置保存在本机浏览器，换设备需重新配置。
        </Typography.Paragraph>

        <Form
          form={form}
          layout="vertical"
          initialValues={DEFAULT_USER_SETTINGS}
          onValuesChange={() => setDirty(true)}
        >
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Card className="card-block" title="创作偏好">
              <Form.Item
                name="defaultStylePreset"
                label="新建项目默认风格"
                extra="创建项目时自动应用，可在工作台内随时修改"
              >
                <Select options={[...STYLE_PRESETS]} />
              </Form.Item>

              <Form.Item
                name="assistantAutoOpen"
                label="进入工作台时打开 AI 助手"
                valuePropName="checked"
              >
                <Switch />
              </Form.Item>
            </Card>

            <Card className="card-block" title="导出">
              <Form.Item
                name="defaultExportFormat"
                label="默认导出格式"
                extra="导出提示词包时使用，后续可在工作台单独选择"
              >
                <Select
                  options={[
                    { value: 'json', label: 'JSON（结构化数据）' },
                    { value: 'markdown', label: 'Markdown（可读文档）' },
                  ]}
                />
              </Form.Item>
            </Card>

            <Card className="card-block" title="AI 服务">
              <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
                Ollama 地址、模型名称、API Key 等连接配置请在 AI 接口页管理。
              </Typography.Paragraph>
              <Button icon={<ApiOutlined />} onClick={() => navigate('/ai-config')}>
                前往 AI 接口配置
              </Button>
            </Card>

            <Card className="card-block" title="关于">
              <Space direction="vertical" size={8}>
                <Typography.Text style={{ color: 'var(--sf-text)' }}>
                  <InfoCircleOutlined style={{ marginRight: 8 }} />
                  StoryFlow — 短剧提示词工作台
                </Typography.Text>
              </Space>
            </Card>

            <Space>
              <Button type="primary" icon={<SaveOutlined />} onClick={save} loading={saving} disabled={!dirty}>
                保存设置
              </Button>
              <Button icon={<ReloadOutlined />} onClick={reset}>
                恢复默认
              </Button>
              <Button icon={<ExportOutlined />} onClick={() => navigate('/projects')}>
                返回项目
              </Button>
            </Space>
          </Space>
        </Form>
      </div>
    </AppLayout>
  );
}
