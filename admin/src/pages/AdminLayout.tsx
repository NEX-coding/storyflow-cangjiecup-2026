import { Button, Layout, Menu, message } from 'antd';
import {
  DashboardOutlined,
  ExperimentOutlined,
  FileTextOutlined,
  LinkOutlined,
  LogoutOutlined,
  MessageOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';

const { Header, Sider, Content } = Layout;

const PAGE_META: Record<string, { title: string; kicker: string }> = {
  dashboard: { title: '仪表盘', kicker: 'Overview' },
  users: { title: '用户管理', kicker: 'Users' },
  feedbacks: { title: '用户反馈', kicker: 'Feedback' },
  templates: { title: 'Prompt 模板', kicker: 'Templates' },
  'ai-models': { title: 'AI 模型', kicker: 'Models' },
};

export default function AdminLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const path = location.pathname.replace(/\/$/, '') || '/';
  const selected = path === '/' ? 'dashboard' : path.split('/').filter(Boolean)[0];
  const meta = PAGE_META[selected] || PAGE_META.dashboard;

  const handleLogout = async () => {
    await logout();
    message.success('已退出登录');
    navigate('/login', { replace: true });
  };

  return (
    <Layout className="admin-shell">
      <Sider className="admin-sider" width={232} theme="dark">
        <div className="admin-brand">
          <div className="admin-brand-mark" aria-hidden>
            S
          </div>
          <div className="admin-brand-text">
            <h1 className="admin-brand-title">StoryFlow</h1>
            <span className="admin-brand-sub">Admin Console</span>
          </div>
        </div>
        <Menu
          className="admin-menu"
          theme="dark"
          mode="inline"
          selectedKeys={[selected]}
          items={[
            {
              key: 'dashboard',
              icon: <DashboardOutlined />,
              label: <Link to="/">仪表盘</Link>,
            },
            {
              key: 'users',
              icon: <TeamOutlined />,
              label: <Link to="/users">用户管理</Link>,
            },
            {
              key: 'feedbacks',
              icon: <MessageOutlined />,
              label: <Link to="/feedbacks">用户反馈</Link>,
            },
            {
              key: 'templates',
              icon: <FileTextOutlined />,
              label: <Link to="/templates">Prompt 模板</Link>,
            },
            {
              key: 'ai-models',
              icon: <ExperimentOutlined />,
              label: <Link to="/ai-models">AI 模型</Link>,
            },
          ]}
        />
      </Sider>
      <Layout>
        <Header className="admin-header">
          <div className="admin-header-meta">
            <span className="admin-header-kicker">{meta.kicker}</span>
            <span className="admin-header-title">{meta.title}</span>
          </div>
          <div className="admin-header-actions">
            <span className="admin-user-chip">
              <span className="admin-user-dot" aria-hidden />
              {user?.nickname || user?.username}
            </span>
            <Button
              type="default"
              icon={<LinkOutlined />}
              href="http://127.0.0.1:5180"
              target="_blank"
              rel="noreferrer"
            >
              用户端
            </Button>
            <Button type="text" icon={<LogoutOutlined />} onClick={() => void handleLogout()}>
              退出
            </Button>
          </div>
        </Header>
        <Content className="admin-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
