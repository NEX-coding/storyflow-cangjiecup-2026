import { useState } from 'react';
import { Button, Card, Form, Input, Select, Typography, message } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { feedbackApi } from '../api/client';
import type { FeedbackType } from '../types';
import { TYPE_OPTIONS } from './feedbackShared';
import '../styles/home.css';

interface FeedbackFormValues {
  type: FeedbackType;
  content: string;
  contact?: string;
}

export default function FeedbackSubmitPage() {
  const navigate = useNavigate();
  const [form] = Form.useForm<FeedbackFormValues>();
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await feedbackApi.submit({
        ...values,
        pageUrl: window.location.pathname,
      });
      message.success('感谢反馈，我们已收到');
      form.resetFields();
      navigate('/feedback/history');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="feedback-panel">
      <Typography.Title level={3} style={{ margin: '0 0 8px', color: 'var(--sf-text)' }}>
        反馈
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 24 }}>
        遇到问题或有改进想法？告诉我们，帮助 StoryFlow 变得更好。
      </Typography.Paragraph>

      <Card className="card-block" title="提交反馈">
        <Form form={form} layout="vertical" initialValues={{ type: 'feature' as FeedbackType }}>
          <Form.Item
            name="type"
            label="反馈类型"
            rules={[{ required: true, message: '请选择反馈类型' }]}
          >
            <Select options={TYPE_OPTIONS.map(({ value, label }) => ({ value, label }))} />
          </Form.Item>

          <Form.Item
            name="content"
            label="详细描述"
            rules={[
              { required: true, message: '请填写反馈内容' },
              { max: 2000, message: '最多 2000 字' },
            ]}
          >
            <Input.TextArea
              rows={6}
              placeholder="请描述你遇到的问题、期望的功能，或任何使用上的建议…"
              showCount
              maxLength={2000}
            />
          </Form.Item>

          <Form.Item
            name="contact"
            label="联系方式（选填）"
            extra="如需回复，可留下邮箱或微信号"
          >
            <Input placeholder="example@email.com" maxLength={128} />
          </Form.Item>

          <Button type="primary" icon={<SendOutlined />} onClick={submit} loading={submitting}>
            提交反馈
          </Button>
        </Form>
      </Card>
    </div>
  );
}
