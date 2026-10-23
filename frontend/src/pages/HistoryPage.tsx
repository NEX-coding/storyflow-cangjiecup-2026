import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Empty, Select, Space, Tag, Typography, message } from 'antd';
import { HistoryOutlined, LinkOutlined, LoginOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { AppLayout } from '../components/AppLayout';
import { LoginModal } from '../components/LoginModal';
import { useAuth } from '../auth/AuthProvider';
import { aiHistoryApi } from '../api/client';
import type { AiHistoryItem, AiHistoryType } from '../types';
import '../styles/home.css';

const TYPE_OPTIONS: { value: AiHistoryType | 'all'; label: string; color: string }[] = [
  { value: 'all', label: '全部类型', color: 'default' },
  { value: 'script_generate', label: '生成剧本', color: 'blue' },
  { value: 'asset_extract', label: '提取资产', color: 'cyan' },
  { value: 'shot_generate', label: '生成分镜', color: 'purple' },
  { value: 'asset_regenerate', label: '资产提示词', color: 'geekblue' },
  { value: 'shot_regenerate', label: '分镜提示词', color: 'volcano' },
  { value: 'assistant_chat', label: 'AI 助手', color: 'green' },
];

function typeMeta(type: AiHistoryType) {
  return TYPE_OPTIONS.find((item) => item.value === type) ?? { value: type, label: type, color: 'default' };
}

function formatDate(iso: string) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function HistoryPage() {
  const navigate = useNavigate();
  const { user, loading: authLoading } = useAuth();
  const isGuest = Boolean(user?.guest);
  const canViewHistory = Boolean(user) && !isGuest;

  const [items, setItems] = useState<AiHistoryItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [typeFilter, setTypeFilter] = useState<AiHistoryType | 'all'>('all');
  const [loginOpen, setLoginOpen] = useState(false);

  const load = useCallback(async () => {
    if (!canViewHistory) {
      setItems([]);
      return;
    }
    setLoading(true);
    try {
      setItems(await aiHistoryApi.list(typeFilter === 'all' ? undefined : typeFilter));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载生成历史失败');
    } finally {
      setLoading(false);
    }
  }, [canViewHistory, typeFilter]);

  useEffect(() => {
    if (authLoading) return;
    load();
  }, [authLoading, load]);

  return (
    <AppLayout>
      <div className="page-shell">
        <Typography.Title level={3} style={{ margin: '0 0 24px', color: 'var(--sf-text)' }}>
          生成历史
        </Typography.Title>

        <Card
          className="card-block"
          loading={authLoading || (canViewHistory && loading)}
          title="AI 使用记录"
          extra={
            canViewHistory ? (
              <Space size={12}>
                <Select
                  value={typeFilter}
                  style={{ width: 140 }}
                  options={TYPE_OPTIONS.map(({ value, label }) => ({ value, label }))}
                  onChange={setTypeFilter}
                />
                {items.length > 0 && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    共 {items.length} 条
                  </Typography.Text>
                )}
              </Space>
            ) : null
          }
        >
          {!authLoading && !canViewHistory ? (
            <Empty
              image={<HistoryOutlined style={{ fontSize: 40, color: 'var(--sf-text-muted)' }} />}
              description={
                <Space direction="vertical" size={8}>
                  <Typography.Text>登录后可查看你的 AI 使用记录</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                    访客模式下不展示生成历史
                  </Typography.Text>
                </Space>
              }
            >
              <Button type="primary" icon={<LoginOutlined />} onClick={() => setLoginOpen(true)}>
                登录 / 注册
              </Button>
            </Empty>
          ) : items.length === 0 && !loading ? (
            <Empty
              image={<HistoryOutlined style={{ fontSize: 40, color: 'var(--sf-text-muted)' }} />}
              description="暂无 AI 使用记录"
            />
          ) : (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {items.map((item) => {
                const meta = typeMeta(item.type);
                return (
                  <div key={item.id} className="feedback-item history-item">
                    <div className="feedback-item-header">
                      <Space size={8} wrap>
                        <Tag color={meta.color}>{meta.label}</Tag>
                        {item.status === 'failed' && <Tag color="error">失败</Tag>}
                        <Typography.Link
                          onClick={() => navigate(`/projects/${item.projectId}`)}
                          style={{ fontSize: 13 }}
                        >
                          <LinkOutlined /> {item.projectTitle}
                        </Typography.Link>
                      </Space>
                      <Typography.Text type="secondary" style={{ fontSize: 12, flexShrink: 0 }}>
                        {formatDate(item.createdAt)}
                      </Typography.Text>
                    </div>
                    <Typography.Paragraph
                      style={{ margin: '8px 0 0', color: 'var(--sf-text)', whiteSpace: 'pre-wrap' }}
                    >
                      {item.summary}
                    </Typography.Paragraph>
                    {item.detail && (
                      <Typography.Paragraph
                        type="secondary"
                        style={{ margin: '6px 0 0', fontSize: 13, whiteSpace: 'pre-wrap' }}
                        ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}
                      >
                        {item.detail}
                      </Typography.Paragraph>
                    )}
                  </div>
                );
              })}
            </Space>
          )}
        </Card>
      </div>
      <LoginModal open={loginOpen} onClose={() => setLoginOpen(false)} />
    </AppLayout>
  );
}
