import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { authService } from '../services/api';
import type { AuthUser } from '../types';

type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated';

type AuthContextValue = {
  status: AuthStatus;
  user: AuthUser | null;
  role: string;
  isAdmin: boolean;
  isDev: boolean;
  canAccessUsers: boolean;
  canAccessServices: boolean;
  loginUrl: string;
  refreshSession: () => Promise<void>;
  logout: () => Promise<void>;
};

const DEFAULT_AUTH_USER: AuthUser = {
  authenticated: false,
  permissions: [],
  allowedServices: [],
  canManageUsers: false,
  canManageServices: false
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function normalizeAuthPayload(payload: Partial<AuthUser> | null): AuthUser {
  if (!payload) {
    return DEFAULT_AUTH_USER;
  }

  return {
    authenticated: Boolean(payload.authenticated),
    name: payload.name,
    email: payload.email,
    role: payload.role,
    permissions: Array.isArray(payload.permissions) ? payload.permissions : [],
    allowedServices: Array.isArray(payload.allowedServices) ? payload.allowedServices : [],
    canManageUsers: Boolean(payload.canManageUsers),
    canManageServices: Boolean(payload.canManageServices)
  };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<AuthUser | null>(null);

  const refreshSession = useCallback(async () => {
    setStatus('loading');
    try {
      const payload = await authService.getSession();
      const normalized = normalizeAuthPayload(payload);
      if (normalized.authenticated) {
        setUser(normalized);
        setStatus('authenticated');
      } else {
        setUser(null);
        setStatus('unauthenticated');
      }
    } catch {
      setUser(null);
      setStatus('unauthenticated');
    }
  }, []);

  const logout = useCallback(async () => {
    try {
      await authService.logout();
    } finally {
      setUser(null);
      setStatus('unauthenticated');
    }
  }, []);

  useEffect(() => {
    void refreshSession();
  }, [refreshSession]);

  const role = String(user?.role || '').toUpperCase();
  const backendOrigin = String(import.meta.env.VITE_BACKEND_ORIGIN || 'http://localhost:8080').replace(/\/$/, '');
  const isAdmin = role.includes('ADMIN') || Boolean(user?.canManageUsers) || Boolean(user?.canManageServices);
  const isDev = role.includes('DEV') || role.includes('DEVELOPER') || (!isAdmin && status === 'authenticated');
  const permissions = Array.isArray(user?.permissions) ? user.permissions.map((item) => String(item).toLowerCase()) : [];
  const canReadServices = permissions.includes('services:read') || permissions.includes('logs:read');

  const value = useMemo<AuthContextValue>(() => ({
    status,
    user,
    role,
    isAdmin,
    isDev,
    canAccessUsers: isAdmin || Boolean(user?.canManageUsers),
    canAccessServices: isAdmin || Boolean(user?.canManageServices) || canReadServices,
    loginUrl: `${backendOrigin}/api/auth/login`,
    refreshSession,
    logout
  }), [status, user, role, isAdmin, isDev, canReadServices, backendOrigin, refreshSession, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
}
