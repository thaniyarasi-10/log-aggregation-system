import axios, { AxiosError, type AxiosRequestConfig } from 'axios';
import type {
  AgentQueryRequest,
  AgentQueryResponse,
  AlertItem,
  AlertsResponse,
  AuthUser,
  LogEvent,
  LogFilters,
  LogQueryParams,
  MetricsResponse,
  ServiceAccessRequest,
  ServiceRecord,
  UserRecord
} from '../types';
import { buildLogQueryParams } from '../utils/time';

const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json'
  }
});

const directApi = axios.create({
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json'
  }
});

api.interceptors.request.use((config) => {
  console.log('API CALL:', config.url, config.params);
  return config;
});

function isNotFound(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 404;
}

function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

function isForbidden(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 403;
}

function sanitizeRequestConfig(config?: AxiosRequestConfig): AxiosRequestConfig | undefined {
  if (!config || !config.params || typeof config.params !== 'object') {
    return config;
  }

  const sanitizedParams = Object.entries(config.params as Record<string, unknown>).reduce<Record<string, unknown>>(
    (acc, [key, value]) => {
      if (value === undefined || value === null) {
        return acc;
      }

      if (typeof value === 'string') {
        const trimmed = value.trim();
        if (!trimmed || trimmed.toLowerCase() === 'undefined' || trimmed.toLowerCase() === 'null') {
          return acc;
        }
      }

      acc[key] = value;
      return acc;
    },
    {}
  );

  return {
    ...config,
    params: sanitizedParams
  };
}

export function extractApiErrorMessage(error: unknown, fallback = 'Something went wrong'): string {
  if (!axios.isAxiosError(error)) {
    return fallback;
  }

  const responseData = error.response?.data as { message?: unknown } | undefined;
  const responseMessage = typeof responseData?.message === 'string' ? responseData.message : '';
  if (responseMessage.trim()) {
    return responseMessage;
  }

  return fallback;
}

async function getWithFallback<T>(
  primary: string,
  fallback: string,
  config?: AxiosRequestConfig
): Promise<T> {
  const safeConfig = sanitizeRequestConfig(config);
  try {
    const response = await api.get<T>(primary, safeConfig);
    return response.data;
  } catch (error) {
    if (!isNotFound(error)) {
      throw error;
    }

    const response = await api.get<T>(fallback, safeConfig);
    return response.data;
  }
}

async function getByPaths<T>(paths: string[], config?: AxiosRequestConfig): Promise<T> {
  const safeConfig = sanitizeRequestConfig(config);
  let lastError: unknown;

  for (const path of paths) {
    try {
      const response = await directApi.get<T>(path, safeConfig);
      return response.data;
    } catch (error) {
      lastError = error;
      if (!isNotFound(error)) {
        throw error;
      }
    }
  }

  throw lastError ?? new Error('Request failed');
}

async function getByPathsAllowForbidden<T>(paths: string[], config?: AxiosRequestConfig): Promise<T> {
  const safeConfig = sanitizeRequestConfig(config);
  let lastError: unknown;

  for (const path of paths) {
    try {
      const response = await directApi.get<T>(path, safeConfig);
      return response.data;
    } catch (error) {
      lastError = error;
      if (!isNotFound(error) && !isForbidden(error)) {
        throw error;
      }
    }
  }

  throw lastError ?? new Error('Request failed');
}

async function postByPaths<TResponse, TBody = unknown>(
  paths: string[],
  body?: TBody,
  config?: AxiosRequestConfig
): Promise<TResponse> {
  let lastError: unknown;

  for (const path of paths) {
    try {
      const response = await directApi.post<TResponse>(path, body, config);
      return response.data;
    } catch (error) {
      lastError = error;
      if (!isNotFound(error)) {
        throw error;
      }
    }
  }

  throw lastError ?? new Error('Request failed');
}

async function deleteByPaths(paths: string[], config?: AxiosRequestConfig): Promise<void> {
  let lastError: unknown;

  for (const path of paths) {
    try {
      await directApi.delete(path, config);
      return;
    } catch (error) {
      lastError = error;
      if (!isNotFound(error)) {
        throw error;
      }
    }
  }

  throw lastError ?? new Error('Request failed');
}

function toMetricsResponse(data: unknown): MetricsResponse {
  const source = (data ?? {}) as Partial<MetricsResponse>;

  return {
    totalLogs: Number(source.totalLogs) || 0,
    errorCount: Number(source.errorCount) || 0,
    errorRate: Number(source.errorRate) || 0,
    avgResponseTime: Number(source.avgResponseTime) || 0,
    p95Latency: Number(source.p95Latency) || 0,
    bucketInterval: typeof source.bucketInterval === 'string' ? source.bucketInterval : '1m',
    throughputOverTime: Array.isArray(source.throughputOverTime)
      ? source.throughputOverTime.map((item) => ({
          time: String(item?.time || ''),
          count: Number(item?.count) || 0,
          intervalSeconds: Number(item?.intervalSeconds) || 0,
          throughputPerSecond: Number(item?.throughputPerSecond) || 0,
          errorCount: Number(item?.errorCount) || 0,
          errorRate: Number(item?.errorRate) || 0,
          avgResponseTime: Number(item?.avgResponseTime) || 0
        }))
      : [],
    levelDistribution: Array.isArray(source.levelDistribution)
      ? source.levelDistribution
          .map((item) => ({
            level: String(item?.level || ''),
            count: Number(item?.count) || 0
          }))
          .filter((item) => item.level.length > 0)
      : []
  };
}

const emptyMetrics: MetricsResponse = {
  totalLogs: 0,
  errorCount: 0,
  errorRate: 0,
  avgResponseTime: 0,
  p95Latency: 0,
  bucketInterval: '1m',
  throughputOverTime: [],
  levelDistribution: []
};

export const apiService = {
  async fetchLogs(filters: LogFilters): Promise<LogEvent[]> {
    const params: LogQueryParams = buildLogQueryParams(filters);

    const data = await getByPaths<LogEvent[]>(['/api/logs', '/logs'], { params });
    return Array.isArray(data) ? data : [];
  },

  async getUsers(): Promise<UserRecord[]> {
    const data = await getByPaths<UserRecord[]>(['/api/admin/users', '/admin/users', '/api/users', '/users']);
    return Array.isArray(data) ? data : [];
  },

  async fetchUsers(): Promise<UserRecord[]> {
    return apiService.getUsers();
  },

  async createUser(payload: {
    id?: string;
    username: string;
    email: string;
    roles: string[];
    services: string[];
  }): Promise<UserRecord> {
    const normalizedRoles = payload.roles || [];
    const normalizedServices = payload.services || [];
    const requestPayload = {
      id: payload.id,
      username: payload.username,
      email: payload.email,
      role: normalizedRoles[0] || '',
      serviceName: normalizedServices[0] || '',
      roles: normalizedRoles,
      services: normalizedServices
    };

    return postByPaths<UserRecord, typeof requestPayload>(['/api/admin/users', '/admin/users'], requestPayload);
  },

  async updateUser(
    userId: string,
    payload: {
      username: string;
      email: string;
      roles: string[];
      services: string[];
    }
  ): Promise<UserRecord> {
    return postByPaths<UserRecord, typeof payload>([`/api/admin/users/${userId}`, `/admin/users/${userId}`], payload);
  },

  async deleteUser(userId: string): Promise<void> {
    await deleteByPaths([`/api/admin/users/${userId}`, `/admin/users/${userId}`]);
  },

  async getServices(): Promise<ServiceRecord[]> {
    const data = await getByPaths<string[]>(['/api/services']);
    if (!Array.isArray(data)) {
      return [];
    }
    return data
      .filter((name): name is string => typeof name === 'string' && name.trim().length > 0)
      .map((name) => ({ name: name.trim().toLowerCase() }));
  },

  async getAdminServices(): Promise<ServiceRecord[]> {
    const data = await getByPaths<ServiceRecord[]>(['/api/admin/services', '/admin/services']);
    return Array.isArray(data) ? data : [];
  },

  async fetchServices(): Promise<ServiceRecord[]> {
    return apiService.getServices();
  },

  async createService(payload: { name: string; description: string }): Promise<ServiceRecord> {
    return postByPaths<ServiceRecord, typeof payload>(['/api/admin/services', '/admin/services'], payload);
  },

  async updateService(serviceId: string, payload: { name: string; description: string }): Promise<ServiceRecord> {
    return postByPaths<ServiceRecord, typeof payload>([`/api/admin/services/${serviceId}`, `/admin/services/${serviceId}`], payload);
  },

  async deleteService(serviceId: string): Promise<void> {
    await deleteByPaths([`/api/admin/services/${serviceId}`, `/admin/services/${serviceId}`]);
  },

  async requestService(payload: { serviceName: string; description?: string }): Promise<ServiceAccessRequest> {
    return postByPaths<ServiceAccessRequest, typeof payload>(
      ['/api/services/request', '/api/services/requests', '/services/request', '/services/requests'],
      payload
    );
  },

  async approveService(
    requestId: string,
    payload?: { comment?: string; description?: string }
  ): Promise<ServiceAccessRequest> {
    return postByPaths<ServiceAccessRequest, typeof payload>(
      [
        `/api/services/${requestId}/approve`,
        `/api/admin/services/requests/${requestId}/approve`,
        `/admin/services/requests/${requestId}/approve`
      ],
      payload || {}
    );
  },

  async rejectService(requestId: string, payload?: { comment?: string }): Promise<ServiceAccessRequest> {
    return postByPaths<ServiceAccessRequest, typeof payload>(
      [
        `/api/services/${requestId}/reject`,
        `/api/admin/services/requests/${requestId}/reject`,
        `/admin/services/requests/${requestId}/reject`
      ],
      payload || {}
    );
  },

  async getServiceRequests(): Promise<ServiceAccessRequest[]> {
    try {
      const data = await getByPaths<ServiceAccessRequest[]>([
        '/api/services/requests',
        '/services/requests',
        '/api/admin/services/requests',
        '/admin/services/requests'
      ], {
        headers: {
          'Cache-Control': 'no-cache',
          Pragma: 'no-cache'
        }
      });
      return Array.isArray(data) ? data : [];
    } catch (error) {
      if (isForbidden(error) || isNotFound(error)) {
        const mine = await getByPaths<ServiceAccessRequest[]>([
          '/api/services/requests/mine',
          '/services/requests/mine'
        ]);
        return Array.isArray(mine) ? mine : [];
      }
      throw error;
    }
  },

  async fetchMetrics(filters: LogFilters): Promise<MetricsResponse> {
    const params = buildLogQueryParams(filters);
    try {
      const response = await getByPaths<unknown>(['/api/logs/metrics', '/logs/metrics'], { params });
      return toMetricsResponse(response);
    } catch {
      return emptyMetrics;
    }
  },

  async queryAgent(payload: AgentQueryRequest): Promise<AgentQueryResponse> {
      console.log('[apiService.queryAgent] Request:', { mode: payload.mode });
    const response = await api.post<AgentQueryResponse>('/agent/query', payload);
    console.log('[apiService.queryAgent] Response:', { status: response.status, data: response.data });
    return response.data;
  },

  async fetchAlerts(): Promise<AlertItem[]> {
    try {
      const response = await api.get<AlertsResponse>('/alerts');
      const grouped = response.data;
      if (!grouped || typeof grouped !== 'object') return [];
      // Flatten the grouped-by-service map into a single sorted list
      return Object.values(grouped)
        .flat()
        .sort((a, b) => {
          // CRITICAL first, then WARNING, then by timestamp descending
          if (a.severity !== b.severity) {
            return a.severity === 'CRITICAL' ? -1 : 1;
          }
          const ta = a.timestamp ? new Date(a.timestamp).getTime() : 0;
          const tb = b.timestamp ? new Date(b.timestamp).getTime() : 0;
          return tb - ta;
        });
    } catch {
      return [];
    }
  }
};

export const authService = {
  async getSession(): Promise<Partial<AuthUser> | null> {
    try {
      const response = await api.get('/auth/me');
      return response.data as Partial<AuthUser>;
    } catch (error) {
      if (isUnauthorized(error)) {
        return null;
      }
      throw error;
    }
  },

  async logout(): Promise<void> {
    try {
      await api.post('/logout');
      return;
    } catch (error) {
      if (axios.isAxiosError(error)) {
        try {
          await api.get('/logout');
          return;
        } catch {
          throw error as AxiosError;
        }
      }
      throw error;
    }
  }
};
