import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthProvider';
import { NavProvider, NavSider } from './components/NavLayout';
import ApiPage from './pages/ApiPage';
import { FeedbackLayout } from './components/FeedbackLayout';
import FeedbackHistoryPage from './pages/FeedbackHistoryPage';
import FeedbackSubmitPage from './pages/FeedbackSubmitPage';
import HistoryPage from './pages/HistoryPage';
import ProjectListPage from './pages/ProjectListPage';
import SettingsPage from './pages/SettingsPage';
import WorkspacePage from './pages/WorkspacePage';
import './styles/home.css';

export default function App() {
  const basename = (import.meta.env.BASE_URL || '/').replace(/\/$/, '') || '/';
  return (
    <BrowserRouter basename={basename === '/' ? undefined : basename}>
      <AuthProvider>
        <NavProvider>
          <div className="app-shell">
            <NavSider />
            <div className="app-content">
              <Routes>
                <Route path="/" element={<Navigate to="/projects" replace />} />
                <Route path="/projects" element={<ProjectListPage />} />
                <Route path="/projects/:id" element={<WorkspacePage />} />
                <Route path="/history" element={<HistoryPage />} />
                <Route path="/ai-config" element={<ApiPage />} />
                <Route path="/feedback" element={<FeedbackLayout />}>
                  <Route index element={<FeedbackSubmitPage />} />
                  <Route path="history" element={<FeedbackHistoryPage />} />
                </Route>
                <Route path="/settings" element={<SettingsPage />} />
                <Route path="*" element={<Navigate to="/projects" replace />} />
              </Routes>
            </div>
          </div>
        </NavProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
