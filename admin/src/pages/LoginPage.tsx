import { useState } from 'react';
import { Button, Card, Form, Input, Typography, message } from 'antd';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

export default function LoginPage() {
  const { user, loading, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<{ username: string; password: string }>();

  const from = (location.state as { from?: string } | null)?.from || '/';

  if (!loading && user) {
    return <Navigate to={from} replace />;
  }

  const onFinish = async (values: { username: string; password: string }) => {
    setSubmitting(true);
    try {
      await login(values.username.trim(), values.password);
      message.success('登录成功');
      navigate(from, { replace: true });
    } catch (e) {
      message.error(e instanceof Error ? e.message : '登录失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="admin-login">
      <Card className="admin-login-card" bordered={false}>
        <div className="admin-login-simple-brand">
          <div className="admin-brand-mark" aria-hidden>
            S
          </div>
          <Typography.Title level={4} className="admin-login-simple-title">
            StoryFlow 管理端
          </Typography.Title>
        </div>

        <Form form={form} layout="vertical" onFinish={onFinish} requiredMark={false} size="large">
          <Form.Item
            name="username"
            rules={[{ required: true, message: '请输入账号' }]}
          >
            <Input placeholder="账号" autoComplete="username" />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password placeholder="密码" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={submitting}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
