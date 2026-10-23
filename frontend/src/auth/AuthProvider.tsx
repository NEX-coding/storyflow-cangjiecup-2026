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
  saveAuthSession,
  type AuthUser,
} from '../lib/auth';

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  sendRegisterCode: (email: string) => Promise<void>;
  register: (email: string, password: string, code: string, nickname?: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(() => getStoredAuthUser());
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    const token = getAuthToken();
    authApi
      .me()
      .then((me) => {
        if (cancelled) return;
        setUser(me);
        if (token) {
          saveAuthSession({ token, user: me });
        }
      })
      .catch(() => {
        if (cancelled) return;
        if (token) {
          clearAuthSession();
        }
        setUser(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const result = await authApi.login(email, password);
    saveAuthSession(result);
    setUser(result.user);
  }, []);

  const sendRegisterCode = useCallback(async (email: string) => {
    await authApi.sendRegisterCode(email);
  }, []);

  const register = useCallback(async (email: string, password: string, code: string, nickname?: string) => {
    const result = await authApi.register(email, password, code, nickname);
    saveAuthSession(result);
    setUser(result.user);
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // ignore network errors on logout
    }
    clearAuthSession();
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({ user, loading, login, sendRegisterCode, register, logout }),
    [user, loading, login, sendRegisterCode, register, logout],
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
