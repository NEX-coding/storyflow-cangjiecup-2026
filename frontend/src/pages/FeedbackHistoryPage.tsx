import { useEffect, useState } from 'react';
import { Card, Empty, Space, Tag, Typography, message } from 'antd';
import { CommentOutlined } from '@ant-design/icons';
import { feedbackApi } from '../api/client';
import type { Feedback } from '../types';
import { formatDate, statusMeta, typeMeta } from './feedbackShared';
import '../styles/home.css';

export default function FeedbackHistoryPage() {
  const [items, setItems] = useState<Feedback[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setItems(await feedbackApi.list());
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载反馈记录失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  return (
    <div className="feedback-panel">
      <Typography.Title level={3} style={{ margin: '0 0 8px', color: 'var(--sf-text)' }}>
        反馈记录
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 24 }}>
        查看你提交过的反馈及处理进度。
      </Typography.Paragraph>

      <Card
        className="card-block"
        title="我的反馈记录"
        loading={loading}
        extra={
          items.length > 0 ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              共 {items.length} 条
            </Typography.Text>
          ) : null
        }
      >
        {items.length === 0 && !loading ? (
          <Empty
            image={<CommentOutlined style={{ fontSize: 40, color: 'var(--sf-text-muted)' }} />}
            description="暂无反馈记录"
          />
        ) : (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {items.map((item) => {
              const meta = typeMeta(item.type);
              const status = statusMeta(item.status);
              return (
                <div key={item.id} className="feedback-item">
                  <div className="feedback-item-header">
                    <Space size={6} wrap>
                      <Tag color={meta.color}>{meta.label}</Tag>
                      <Tag color={status.color}>{status.label}</Tag>
                    </Space>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {formatDate(item.createdAt)}
                    </Typography.Text>
                  </div>
                  <Typography.Paragraph
                    style={{
                      margin: '8px 0 0',
                      color: 'var(--sf-text-secondary)',
                      whiteSpace: 'pre-wrap',
                    }}
                  >
                    {item.content}
                  </Typography.Paragraph>
                  {item.adminReply && (
                    <Typography.Paragraph
                      style={{
                        margin: '10px 0 0',
                        padding: '10px 12px',
                        borderRadius: 8,
                        background: 'var(--sf-bg-soft, rgba(255,255,255,0.04))',
                        color: 'var(--sf-text-secondary)',
                        whiteSpace: 'pre-wrap',
                      }}
                    >
                      <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
                        管理员回复
                        {item.handledAt ? ` · ${formatDate(item.handledAt)}` : ''}
                      </Typography.Text>
                      {item.adminReply}
                    </Typography.Paragraph>
                  )}
                  {item.contact && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      联系方式：{item.contact}
                    </Typography.Text>
                  )}
                </div>
              );
            })}
          </Space>
        )}
      </Card>
    </div>
  );
}
