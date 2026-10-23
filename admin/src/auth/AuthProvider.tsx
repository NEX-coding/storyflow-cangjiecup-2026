import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { authApi } from '../api/client';
import {
  clearAuthSession,
  getAuthToken,
  getStoredAuthUser,
  isAdminUser,
  saveAuthSession,
  type AuthUser,
} from '../lib/auth';

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => {
    const stored = getStoredAuthUser();
    return isAdminUser(stored) ? stored : null;
  });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const token = getAuthToken();
    if (!token) {
      clearAuthSession();
      setUser(null);
      setLoading(false);
      return;
    }

    authApi
      .me()
      .then((me) => {
        if (cancelled) return;
        if (!isAdminUser(me)) {
          clearAuthSession();
          setUser(null);
          return;
        }
        saveAuthSession({ token, admin: me });
        setUser(me);
      })
      .catch(() => {
        if (cancelled) return;
        clearAuthSession();
        setUser(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const result = await authApi.login(username, password);
    if (!isAdminUser(result.admin)) {
      clearAuthSession();
      throw new Error('该账号不是管理员');
    }
    saveAuthSession(result);
    setUser(result.admin);
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // ignore
    }
    clearAuthSession();
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({ user, loading, login, logout }),
    [user, loading, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return ctx;
}
