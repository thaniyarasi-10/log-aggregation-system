export interface LogEventTags {
    team?: string;
    version?: string;
    [key: string]: string | undefined;
}

export interface LogEvent {
    timestamp: string;
    level: "ERROR" | "INFO" | "WARN" | "DEBUG" | string;
    service: string;
    instance: string;
    environment: string;
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
    tags?: LogEventTags;
}

export interface Metrics {
    errorRate: number;
    avgResponseTime: number;
    throughput: number;
    p95Latency: number;
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

export interface Alert {
    service: string;
    message: string;
    count: number;
    severity: 'WARNING' | 'CRITICAL' | string;
    timestamp: string;
}

export type AlertsGrouped = Record<string, Alert[]>;

export interface FilterOptions {
    service?: string;
    environment?: string;
    level?: string[];
    traceId?: string;
    message?: string;
    timePreset?: '5m' | '15m' | '1h' | '24h' | '7d' | '15d' | 'custom';
    from?: string; // ISO string
    to?: string;   // ISO string
    page?: number;
    size?: number;
}
