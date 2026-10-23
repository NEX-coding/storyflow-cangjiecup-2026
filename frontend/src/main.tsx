import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import './index.css';
import './styles/home.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#6366f1',
          colorBgBase: '#0b0d11',
          colorBgContainer: '#141820',
          colorBgElevated: '#141820',
          colorBorder: 'rgba(255, 255, 255, 0.08)',
          colorBorderSecondary: 'rgba(255, 255, 255, 0.08)',
          colorText: '#f3f4f6',
          colorTextSecondary: '#9ca3af',
          colorTextTertiary: '#6b7280',
          colorTextQuaternary: '#4b5563',
          colorFillSecondary: 'rgba(255, 255, 255, 0.06)',
          colorFillTertiary: 'rgba(255, 255, 255, 0.04)',
          borderRadius: 10,
          fontFamily:
            "-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Segoe UI', sans-serif",
        },
        components: {
          Message: {
            contentBg: '#141820',
            colorText: '#f3f4f6',
          },
          Notification: {
            colorBgElevated: '#141820',
            colorText: '#f3f4f6',
            colorTextHeading: '#f3f4f6',
          },
          Card: {
            colorBgContainer: '#141820',
            colorBorderSecondary: 'rgba(255, 255, 255, 0.08)',
            colorTextHeading: '#f3f4f6',
          },
          Modal: {
            contentBg: '#141820',
            headerBg: 'transparent',
            titleColor: '#f3f4f6',
            colorText: '#f3f4f6',
            colorTextHeading: '#f3f4f6',
            colorIcon: '#9ca3af',
            colorIconHover: '#f3f4f6',
          },
          Alert: {
            colorInfoBg: 'rgba(59, 130, 246, 0.08)',
            colorInfoBorder: 'rgba(59, 130, 246, 0.22)',
          },
        },
      }}
    >
      <App />
    </ConfigProvider>
  </React.StrictMode>,
);
