const TOKEN_KEY = 'storyflow-admin-auth-token';
const USER_KEY = 'storyflow-admin-auth-user';

export interface AuthUser {
  id: number;
  username: string;
  nickname: string;
  role: string;
}

export interface AuthResult {
  token: string;
  admin: AuthUser;
}

export function getAuthToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function getStoredAuthUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as AuthUser;
  } catch {
    return null;
  }
}

export function saveAuthSession(result: AuthResult) {
  localStorage.setItem(TOKEN_KEY, result.token);
  localStorage.setItem(USER_KEY, JSON.stringify(result.admin));
}

export function clearAuthSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export function authHeaders(): Record<string, string> {
  const token = getAuthToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function isAdminUser(user: AuthUser | null | undefined): boolean {
  return Boolean(user && user.role === 'ADMIN' && user.username);
}
