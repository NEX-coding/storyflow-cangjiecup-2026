import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#3d9cfd',
          colorInfo: '#3d9cfd',
          colorSuccess: '#34d399',
          colorWarning: '#fbbf24',
          colorError: '#f87171',
          colorBgBase: '#0b0d12',
          colorBgContainer: '#12151c',
          colorBorder: 'rgba(255,255,255,0.08)',
          colorText: '#eef1f6',
          colorTextSecondary: '#9aa3b5',
          borderRadius: 10,
          fontFamily:
            "'IBM Plex Sans', 'PingFang SC', 'Hiragino Sans GB', 'Noto Sans SC', sans-serif",
          controlHeight: 40,
        },
        components: {
          Button: {
            primaryShadow: '0 6px 16px rgba(61, 156, 253, 0.28)',
          },
          Card: {
            headerBg: 'transparent',
          },
          Table: {
            headerBg: '#181c27',
            rowHoverBg: 'rgba(255,255,255,0.03)',
          },
          Menu: {
            darkItemBg: 'transparent',
            darkSubMenuItemBg: 'transparent',
          },
        },
      }}
    >
      <App />
    </ConfigProvider>
  </React.StrictMode>,
);
