import { useEffect, useState } from 'react';
import { Button, Form, Input, InputNumber, Modal, Switch, Table, Tag, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { adminApi } from '../api/client';
import type { PromptTemplate } from '../types';

export default function AdminTemplates() {
  const [templates, setTemplates] = useState<PromptTemplate[]>([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Partial<PromptTemplate>>({});
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      setTemplates(await adminApi.listTemplates());
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const openEdit = (tpl?: PromptTemplate) => {
    const initial = tpl || { category: '', name: '', content: '', version: 1, active: true };
    setEditing(initial);
    form.setFieldsValue(initial);
    setOpen(true);
  };

  const save = async () => {
    const values = await form.validateFields();
    try {
      await adminApi.saveTemplate({ ...editing, ...values });
      message.success('已保存');
      setOpen(false);
      void load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h2>Prompt 模板</h2>
          <p>管理剧本、资产、分镜与助手等系统提示词模板。</p>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit()}>
          新增模板
        </Button>
      </div>

      <Table
        className="admin-table"
        rowKey="id"
        loading={loading}
        dataSource={templates}
        pagination={{ pageSize: 10, hideOnSinglePage: true }}
        columns={[
          {
            title: '分类',
            dataIndex: 'category',
            render: (v: string) => <code style={{ color: 'var(--admin-accent)' }}>{v}</code>,
          },
          { title: '名称', dataIndex: 'name' },
          { title: '版本', dataIndex: 'version', width: 88 },
          {
            title: '状态',
            width: 100,
            render: (_, r) =>
              r.active ? <Tag color="success">启用</Tag> : <Tag>停用</Tag>,
          },
          {
            title: '操作',
            width: 100,
            render: (_, r) => (
              <Button size="small" type="link" onClick={() => openEdit(r)}>
                编辑
              </Button>
            ),
          },
        ]}
      />

      <Modal
        title={editing.id ? '编辑 Prompt 模板' : '新增 Prompt 模板'}
        open={open}
        onOk={() => void save()}
        onCancel={() => setOpen(false)}
        width={720}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="category" label="分类" rules={[{ required: true }]}>
            <Input placeholder="script_generate / asset_extract / shot_generate / assistant" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="version" label="版本" rules={[{ required: true }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="active" label="启用" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
          <Form.Item name="content" label="内容" rules={[{ required: true }]}>
            <Input.TextArea rows={12} style={{ fontFamily: "'IBM Plex Mono', monospace" }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
