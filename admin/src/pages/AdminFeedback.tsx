import { useEffect, useState } from 'react';
import {
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { MessageOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { adminApi } from '../api/client';
import type { AdminFeedback } from '../types';

const TYPE_META: Record<string, { label: string; color: string }> = {
  bug: { label: '问题反馈', color: 'error' },
  feature: { label: '功能建议', color: 'processing' },
  improvement: { label: '体验优化', color: 'success' },
  other: { label: '其他', color: 'default' },
};

const STATUS_META: Record<string, { label: string; color: string }> = {
  pending: { label: '待处理', color: 'warning' },
  processing: { label: '处理中', color: 'processing' },
  resolved: { label: '已解决', color: 'success' },
  closed: { label: '已关闭', color: 'default' },
};

function formatTime(value?: string) {
  if (!value) return '—';
  try {
    return new Date(value).toLocaleString('zh-CN', { hour12: false });
  } catch {
    return value;
  }
}

export default function AdminFeedback() {
  const [items, setItems] = useState<AdminFeedback[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [q, setQ] = useState('');
  const [keyword, setKeyword] = useState('');
  const [type, setType] = useState<string | undefined>();
  const [status, setStatus] = useState<string | undefined>();
  const [current, setCurrent] = useState<AdminFeedback | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<{ status: string; adminReply?: string }>();

  const load = async (nextPage = page, nextSize = pageSize) => {
    setLoading(true);
    try {
      const data = await adminApi.listFeedbacks({
        q: keyword || undefined,
        type,
        status,
        page: nextPage,
        size: nextSize,
      });
      setItems(data.items);
      setTotal(data.total);
      setPage(data.page);
      setPageSize(data.size);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载反馈失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load(1, pageSize);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, type, status]);

  const openHandle = (item: AdminFeedback) => {
    setCurrent(item);
    form.setFieldsValue({
      status: item.status || 'pending',
      adminReply: item.adminReply || '',
    });
  };

  const saveHandle = async () => {
    if (!current) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      const updated = await adminApi.handleFeedback(current.id, {
        status: values.status,
        adminReply: values.adminReply,
      });
      setItems((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      setCurrent(null);
      message.success('已更新处理状态');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h2>用户反馈</h2>
          <p>查看用户提交的问题与建议，更新处理进度并回复。</p>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
          刷新
        </Button>
      </div>

      <div className="admin-toolbar">
        <Space wrap size={12}>
          <Input
            allowClear
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onPressEnter={() => setKeyword(q.trim())}
            placeholder="搜索内容 / 联系方式"
            prefix={<SearchOutlined />}
            style={{ width: 240 }}
          />
          <Select
            allowClear
            placeholder="类型"
            style={{ width: 130 }}
            value={type}
            onChange={setType}
            options={Object.entries(TYPE_META).map(([value, meta]) => ({
              value,
              label: meta.label,
            }))}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 130 }}
            value={status}
            onChange={setStatus}
            options={Object.entries(STATUS_META).map(([value, meta]) => ({
              value,
              label: meta.label,
            }))}
          />
          <Button type="primary" onClick={() => setKeyword(q.trim())}>
            查询
          </Button>
        </Space>
      </div>

      <Table
        className="admin-table"
        rowKey="id"
        loading={loading}
        dataSource={items}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (count) => `共 ${count} 条`,
          onChange: (nextPage, nextSize) => {
            void load(nextPage, nextSize);
          },
        }}
        columns={[
          {
            title: '用户',
            width: 180,
            render: (_, r) => (
              <div className="admin-user-cell">
                <strong>{r.userNickname || '—'}</strong>
                <span>{r.userEmail || `UID ${r.userId}`}</span>
              </div>
            ),
          },
          {
            title: '类型',
            dataIndex: 'type',
            width: 110,
            render: (value: string) => {
              const meta = TYPE_META[value] || TYPE_META.other;
              return <Tag color={meta.color}>{meta.label}</Tag>;
            },
          },
          {
            title: '内容',
            dataIndex: 'content',
            ellipsis: true,
            render: (value: string) => (
              <Typography.Paragraph
                ellipsis={{ rows: 2 }}
                style={{ marginBottom: 0, color: 'var(--admin-text-secondary)' }}
              >
                {value}
              </Typography.Paragraph>
            ),
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 100,
            render: (value: string) => {
              const meta = STATUS_META[value] || STATUS_META.pending;
              return <Tag color={meta.color}>{meta.label}</Tag>;
            },
          },
          {
            title: '提交时间',
            dataIndex: 'createdAt',
            width: 170,
            render: (v: string) => formatTime(v),
          },
          {
            title: '操作',
            width: 100,
            render: (_, r) => (
              <Button
                type="link"
                size="small"
                icon={<MessageOutlined />}
                onClick={() => openHandle(r)}
              >
                处理
              </Button>
            ),
          },
        ]}
      />

      <Modal
        title="处理反馈"
        open={Boolean(current)}
        onCancel={() => setCurrent(null)}
        onOk={() => void saveHandle()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={640}
        destroyOnClose
      >
        {current && (
          <>
            <div className="admin-feedback-detail">
              <Space size={8} wrap>
                <Tag color={(TYPE_META[current.type] || TYPE_META.other).color}>
                  {(TYPE_META[current.type] || TYPE_META.other).label}
                </Tag>
                <Typography.Text type="secondary">
                  {current.userNickname} · {current.userEmail}
                </Typography.Text>
              </Space>
              <Typography.Paragraph style={{ marginTop: 12, whiteSpace: 'pre-wrap' }}>
                {current.content}
              </Typography.Paragraph>
              {current.contact && (
                <Typography.Text type="secondary">联系方式：{current.contact}</Typography.Text>
              )}
              {current.pageUrl && (
                <div>
                  <Typography.Text type="secondary">页面：{current.pageUrl}</Typography.Text>
                </div>
              )}
            </div>
            <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
              <Form.Item
                name="status"
                label="处理状态"
                rules={[{ required: true, message: '请选择状态' }]}
              >
                <Select
                  options={Object.entries(STATUS_META).map(([value, meta]) => ({
                    value,
                    label: meta.label,
                  }))}
                />
              </Form.Item>
              <Form.Item name="adminReply" label="管理员回复">
                <Input.TextArea rows={4} placeholder="可选，回复会展示给用户" maxLength={2000} showCount />
              </Form.Item>
            </Form>
          </>
        )}
      </Modal>
    </div>
  );
}
