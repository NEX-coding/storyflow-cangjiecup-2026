import { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic } from 'antd';
import {
  CommentOutlined,
  FileTextOutlined,
  MessageOutlined,
  ProjectOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { adminApi } from '../api/client';
import type { DashboardStats } from '../types';

export default function AdminDashboard() {
  const [stats, setStats] = useState<DashboardStats>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    adminApi
      .dashboard()
      .then(setStats)
      .catch(() => setStats({}))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h2>运营概览</h2>
          <p>查看用户规模、项目数量、反馈与 AI 调用情况。</p>
        </div>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={8}>
          <Card className="admin-card admin-stat-card" loading={loading}>
            <Statistic
              title="注册用户"
              value={stats.userCount || 0}
              prefix={<UserOutlined style={{ color: 'var(--admin-accent)', marginRight: 8 }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8}>
          <Card className="admin-card admin-stat-card" loading={loading}>
            <Statistic
              title="项目总数"
              value={stats.projectCount || 0}
              prefix={<ProjectOutlined style={{ color: 'var(--admin-accent)', marginRight: 8 }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8}>
          <Card className="admin-card admin-stat-card" loading={loading}>
            <Statistic
              title="待处理反馈"
              value={stats.pendingFeedbackCount || 0}
              prefix={<MessageOutlined style={{ color: 'var(--admin-warning)', marginRight: 8 }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8}>
          <Card className="admin-card admin-stat-card" loading={loading}>
            <Statistic
              title="今日 AI 调用"
              value={stats.todayAiCallCount || 0}
              prefix={<ThunderboltOutlined style={{ color: 'var(--admin-warning)', marginRight: 8 }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8}>
          <Card className="admin-card admin-stat-card" loading={loading}>
            <Statistic
              title="AI 对话数"
              value={stats.aiCallCount || 0}
              prefix={<CommentOutlined style={{ color: 'var(--admin-success)', marginRight: 8 }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8}>
          <Card className="admin-card admin-stat-card" loading={loading}>
            <Statistic
              title="Prompt 模板"
              value={stats.templateCount || 0}
              prefix={<FileTextOutlined style={{ color: 'var(--admin-accent)', marginRight: 8 }} />}
            />
          </Card>
        </Col>
      </Row>

      <div className="admin-quick-links">
        <Link to="/users">管理用户 →</Link>
        <Link to="/feedbacks">处理反馈 →</Link>
        <Link to="/templates">编辑 Prompt 模板 →</Link>
        <Link to="/ai-models">配置 AI 模型 →</Link>
      </div>
    </div>
  );
}
