import { useEffect, useState } from 'react';
import {
  Button,
  Input,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  message,
} from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { adminApi } from '../api/client';
import type { AdminUser } from '../types';

function formatTime(value?: string) {
  if (!value) return '—';
  try {
    return new Date(value).toLocaleString('zh-CN', { hour12: false });
  } catch {
    return value;
  }
}

export default function AdminUsers() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [q, setQ] = useState('');
  const [keyword, setKeyword] = useState('');
  const [role, setRole] = useState<string | undefined>();
  const [enabled, setEnabled] = useState<boolean | undefined>();
  const [togglingId, setTogglingId] = useState<number | null>(null);

  const load = async (nextPage = page, nextSize = pageSize) => {
    setLoading(true);
    try {
      const data = await adminApi.listUsers({
        q: keyword || undefined,
        role,
        enabled,
        page: nextPage,
        size: nextSize,
      });
      setUsers(data.items);
      setTotal(data.total);
      setPage(data.page);
      setPageSize(data.size);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载用户失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load(1, pageSize);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, role, enabled]);

  const onSearch = () => {
    setKeyword(q.trim());
  };

  const toggleStatus = async (user: AdminUser, next: boolean) => {
    setTogglingId(user.id);
    try {
      const updated = await adminApi.updateUserStatus(user.id, next);
      setUsers((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      message.success(next ? '已启用账号' : '已停用账号');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '更新失败');
    } finally {
      setTogglingId(null);
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h2>用户管理</h2>
          <p>搜索、筛选平台用户，并启用或停用账号。</p>
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
            onPressEnter={onSearch}
            placeholder="搜索邮箱 / 昵称"
            prefix={<SearchOutlined />}
            style={{ width: 240 }}
          />
          <Select
            allowClear
            placeholder="角色"
            style={{ width: 120 }}
            value={role}
            onChange={(value) => setRole(value)}
            options={[
              { value: 'USER', label: '普通用户' },
              { value: 'ADMIN', label: '管理员' },
            ]}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 120 }}
            value={enabled === undefined ? undefined : enabled ? '1' : '0'}
            onChange={(value) => {
              if (value === undefined) setEnabled(undefined);
              else setEnabled(value === '1');
            }}
            options={[
              { value: '1', label: '启用' },
              { value: '0', label: '停用' },
            ]}
          />
          <Button type="primary" onClick={onSearch}>
            查询
          </Button>
        </Space>
      </div>

      <Table
        className="admin-table"
        rowKey="id"
        loading={loading}
        dataSource={users}
        pagination={{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (count) => `共 ${count} 人`,
          onChange: (nextPage, nextSize) => {
            void load(nextPage, nextSize);
          },
        }}
        columns={[
          {
            title: '用户',
            render: (_, r) => (
              <div className="admin-user-cell">
                <strong>{r.nickname || '—'}</strong>
                <span>{r.email}</span>
              </div>
            ),
          },
          {
            title: '角色',
            dataIndex: 'role',
            width: 110,
            render: (roleValue: string, r) => (
              <Space size={4} wrap>
                <Tag color={roleValue === 'ADMIN' ? 'blue' : 'default'}>
                  {roleValue === 'ADMIN' ? '管理员' : '用户'}
                </Tag>
                {r.systemAccount && <Tag>系统</Tag>}
              </Space>
            ),
          },
          {
            title: '项目',
            dataIndex: 'projectCount',
            width: 80,
          },
          {
            title: 'AI 调用',
            dataIndex: 'aiCallCount',
            width: 96,
          },
          {
            title: '注册时间',
            dataIndex: 'createdAt',
            width: 180,
            render: (v: string) => formatTime(v),
          },
          {
            title: '状态',
            width: 120,
            render: (_, r) => {
              const locked = r.role === 'ADMIN' || r.systemAccount;
              return (
                <Switch
                  checkedChildren="启用"
                  unCheckedChildren="停用"
                  checked={r.enabled}
                  disabled={locked}
                  loading={togglingId === r.id}
                  onChange={(checked) => void toggleStatus(r, checked)}
                />
              );
            },
          },
        ]}
      />
    </div>
  );
}
