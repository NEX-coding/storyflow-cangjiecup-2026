import { useEffect, useState } from 'react';
import { Button, Form, Input, Modal, Space, Tabs, message } from 'antd';
import { useAuth } from '../auth/AuthProvider';

interface LoginModalProps {
  open: boolean;
  onClose: () => void;
}

const CODE_COOLDOWN = 60;

export function LoginModal({ open, onClose }: LoginModalProps) {
  const { login, register, sendRegisterCode } = useAuth();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [submitting, setSubmitting] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [loginForm] = Form.useForm<{ email: string; password: string }>();
  const [registerForm] = Form.useForm<{
    email: string;
    code: string;
    password: string;
    nickname?: string;
  }>();

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = window.setTimeout(() => setCooldown((v) => v - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [cooldown]);

  useEffect(() => {
    if (!open) {
      setCooldown(0);
      setMode('login');
      loginForm.resetFields();
      registerForm.resetFields();
      return;
    }
    // 打开时清空，避免残留；密码由浏览器管理器填充时也不主动保留
    loginForm.resetFields();
    registerForm.resetFields();
  }, [open, loginForm, registerForm]);

  const handleClose = () => {
    if (submitting || sendingCode) return;
    onClose();
  };

  const onLogin = async (values: { email: string; password: string }) => {
    setSubmitting(true);
    try {
      await login(values.email.trim(), values.password);
      message.success('登录成功');
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '登录失败');
    } finally {
      setSubmitting(false);
    }
  };

  const onSendCode = async () => {
    if (sendingCode || cooldown > 0) return;
    try {
      const values = await registerForm.validateFields(['email']);
      const email = values.email.trim();
      setSendingCode(true);
      const hide = message.loading('正在发送验证码…', 0);
      try {
        await sendRegisterCode(email);
        hide();
        message.success('验证码已发送，请查收邮箱（含垃圾箱）');
        setCooldown(CODE_COOLDOWN);
        registerForm.setFieldsValue({ code: undefined });
      } catch (e) {
        hide();
        throw e;
      }
    } catch (e) {
      if (e && typeof e === 'object' && 'errorFields' in e) {
        return;
      }
      message.error(e instanceof Error ? e.message : '验证码发送失败');
    } finally {
      setSendingCode(false);
    }
  };

  const onRegister = async (values: {
    email: string;
    code: string;
    password: string;
    nickname?: string;
  }) => {
    setSubmitting(true);
    try {
      await register(values.email.trim(), values.password, values.code.trim(), values.nickname?.trim());
      message.success('注册成功');
      onClose();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '注册失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      onCancel={handleClose}
      footer={null}
      destroyOnClose
      title={mode === 'login' ? '登录 StoryFlow' : '邮箱注册'}
      width={400}
    >
      <Tabs
        activeKey={mode}
        onChange={(key) => setMode(key as 'login' | 'register')}
        items={[
          {
            key: 'login',
            label: '登录',
            children: (
              <>
                <Form
                  form={loginForm}
                  layout="vertical"
                  onFinish={onLogin}
                  requiredMark={false}
                  autoComplete="off"
                >
                  <Form.Item
                    name="email"
                    label="邮箱"
                    rules={[
                      { required: true, message: '请输入邮箱' },
                      { type: 'email', message: '邮箱格式不正确' },
                    ]}
                  >
                    <Input autoComplete="off" name="storyflow-login-email" placeholder="you@example.com" />
                  </Form.Item>
                  <Form.Item
                    name="password"
                    label="密码"
                    rules={[{ required: true, message: '请输入密码' }]}
                  >
                    <Input.Password
                      autoComplete="new-password"
                      name="storyflow-login-password"
                      placeholder="密码"
                    />
                  </Form.Item>
                  <Button type="primary" htmlType="submit" block loading={submitting}>
                    登录
                  </Button>
                </Form>
              </>
            ),
          },
          {
            key: 'register',
            label: '注册',
            children: (
              <Form
                form={registerForm}
                layout="vertical"
                onFinish={onRegister}
                requiredMark={false}
                autoComplete="off"
              >
                <Form.Item
                  name="email"
                  label="邮箱"
                  rules={[
                    { required: true, message: '请输入邮箱' },
                    { type: 'email', message: '邮箱格式不正确' },
                  ]}
                >
                  <Input autoComplete="off" name="storyflow-register-email" placeholder="you@example.com" />
                </Form.Item>
                <Form.Item label="邮箱验证码" required>
                  <Space.Compact style={{ width: '100%' }}>
                    <Form.Item
                      name="code"
                      noStyle
                      rules={[
                        { required: true, message: '请输入验证码' },
                        { pattern: /^\d{6}$/, message: '请输入 6 位数字验证码' },
                      ]}
                    >
                      <Input
                        maxLength={6}
                        placeholder="6 位验证码"
                        autoComplete="one-time-code"
                        style={{ width: '100%' }}
                      />
                    </Form.Item>
                    <Button
                      type="default"
                      loading={sendingCode}
                      disabled={cooldown > 0}
                      onClick={() => {
                        void onSendCode();
                      }}
                    >
                      {cooldown > 0 ? `${cooldown}s` : '发送验证码'}
                    </Button>
                  </Space.Compact>
                </Form.Item>
                <Form.Item name="nickname" label="昵称">
                  <Input placeholder="可选，默认取邮箱前缀" maxLength={40} />
                </Form.Item>
                <Form.Item
                  name="password"
                  label="密码"
                  rules={[
                    { required: true, message: '请输入密码' },
                    { min: 6, message: '至少 6 位' },
                  ]}
                >
                  <Input.Password
                    autoComplete="new-password"
                    name="storyflow-register-password"
                    placeholder="至少 6 位"
                  />
                </Form.Item>
                <Button type="primary" htmlType="submit" block loading={submitting}>
                  注册并登录
                </Button>
              </Form>
            ),
          },
        ]}
      />
    </Modal>
  );
}
