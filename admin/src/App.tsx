import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthProvider';
import { RequireAdmin } from './components/RequireAdmin';
import AdminLayout from './pages/AdminLayout';
import AdminDashboard from './pages/AdminDashboard';
import AdminUsers from './pages/AdminUsers';
import AdminFeedback from './pages/AdminFeedback';
import AdminTemplates from './pages/AdminTemplates';
import AdminAiModels from './pages/AdminAiModels';
import LoginPage from './pages/LoginPage';

export default function App() {
  const basename = (import.meta.env.BASE_URL || '/').replace(/\/$/, '') || '/';
  return (
    <BrowserRouter basename={basename === '/' ? undefined : basename}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={
              <RequireAdmin>
                <AdminLayout />
              </RequireAdmin>
            }
          >
            <Route index element={<AdminDashboard />} />
            <Route path="users" element={<AdminUsers />} />
            <Route path="feedbacks" element={<AdminFeedback />} />
            <Route path="templates" element={<AdminTemplates />} />
            <Route path="ai-models" element={<AdminAiModels />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
