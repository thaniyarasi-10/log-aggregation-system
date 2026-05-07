export type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG' | string;

export interface LogEvent {
  id?: string;
  "@timestamp"?: string;
  level: LogLevel;
  service: string;
  instance?: string;
  environment?: string;
  message: string;
  traceId?: string;
  spanId?: string;
  userId?: string;
  endpoint?: string;
  method?: string;
  statusCode?: number;
  responseTime?: number;
  errorCode?: string;
  errorDetails?: string;
}

export interface LogFilters {
  timeRange: '5m' | '15m' | '1h' | '24h' | '7d' | '15d';
  service: string;
  level: string;
  search: string;
}

export interface LogQueryParams {
  service?: string;
  environment?: string;
  level?: string;
  traceId?: string;
  message?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  timePreset?: string;
}

export interface MetricsTimeBucket {
  time: string;
  count: number;
  intervalSeconds: number;
  throughputPerSecond: number;
  errorCount: number;
  errorRate: number;
  avgResponseTime: number;
}

export interface LevelDistributionItem {
  level: string;
  count: number;
}

export interface MetricsResponse {
  totalLogs: number;
  errorCount: number;
  errorRate: number;
  avgResponseTime: number;
  p95Latency: number;
  bucketInterval: string;
  throughputOverTime: MetricsTimeBucket[];
  levelDistribution: LevelDistributionItem[];
}

export interface UserRecord {
  id: string;
  username: string;
  name?: string;
  email: string;
  services?: string[];
  roles?: string[];
  role?: string;
}

export interface ServiceRecord {
  id?: string;
  name: string;
  description?: string;
  status?: string;
  active?: boolean;
}

export interface ServiceAccessRequest {
  id: string;
  requestedByUserId: string;
  requestedByEmail: string;
  serviceName: string;
  description?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | string;
  reviewComment?: string;
  createdAt?: string;
  reviewedAt?: string;
}

export interface AuthUser {
  authenticated: boolean;
  name?: string;
  email?: string;
  role?: string;
  permissions: string[];
  allowedServices: string[];
  assignedServices?: string[];
  canManageUsers: boolean;
  canManageServices: boolean;
}

export type AgentMode = 'qa' | 'summary';

export interface AgentQueryRequest {
  query: string;
  mode: AgentMode;
  role: string;
  services: string[];
}

export interface AgentQueryResponse {
  success?: boolean;
  answer?: string;
  pdf_url?: string;
  message?: string;
}

/** A single alert item as returned by the backend /alerts endpoint */
export interface AlertItem {
  service: string;
  message: string;
  count: number;
  severity: string;
  timestamp: string | null;
}

/** Raw response shape from GET /api/alerts — keyed by service name */
export type AlertsResponse = Record<string, AlertItem[]>;
