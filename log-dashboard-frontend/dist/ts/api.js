const API_BASE_URL = 'http://localhost:8080';
const SESSION_FETCH_OPTIONS = {
    credentials: 'include'
};
export class ApiClient {
    static async fetchLogs(filters) {
        try {
            const params = new URLSearchParams();
            if (filters.service)
                params.append('service', filters.service);
            if (filters.environment)
                params.append('environment', filters.environment);
            if (filters.level && filters.level.length > 0) {
                params.append('level', filters.level[0]);
            }
            if (filters.traceId)
                params.append('traceId', filters.traceId);
            if (filters.message)
                params.append('message', filters.message);
            if (filters.from)
                params.append('from', filters.from);
            if (filters.to)
                params.append('to', filters.to);
            if (typeof filters.page === 'number')
                params.append('page', String(filters.page));
            if (typeof filters.size === 'number')
                params.append('size', String(filters.size));
            const queryString = params.toString();
            const url = `${API_BASE_URL}/logs${queryString ? '?' + queryString : ''}`;
            const response = await fetch(url, SESSION_FETCH_OPTIONS);
            if (!response.ok) {
                console.warn(`Backend error (${response.status}) for ${url}, returning empty array.`);
                return [];
            }
            return await response.json();
        }
        catch (error) {
            console.error("Failed to fetch logs:", error);
            return [];
        }
    }
    static async fetchAlerts() {
        try {
            const response = await fetch(`${API_BASE_URL}/alerts`, SESSION_FETCH_OPTIONS);
            if (!response.ok) {
                console.warn(`Backend error (${response.status}) for /alerts, returning empty alerts.`);
                return {};
            }
            return await response.json();
        }
        catch (error) {
            console.error('Failed to fetch alerts:', error);
            return {};
        }
    }
    static async fetchServices(filters = {}) {
        try {
            const url = `${API_BASE_URL}/api/services`;
            const response = await fetch(url, SESSION_FETCH_OPTIONS);
            if (!response.ok) {
                console.warn(`Backend error (${response.status}) for ${url}, returning empty service list.`);
                return [];
            }
            const data = await response.json();
            return Array.isArray(data)
                ? data.filter((item) => typeof item === 'string' && item.trim().length > 0)
                : [];
        }
        catch (error) {
            console.error('Failed to fetch services:', error);
            return [];
        }
    }
    static async fetchMetrics(filters) {
        const empty = {
            totalLogs: 0,
            errorCount: 0,
            errorRate: 0,
            avgResponseTime: 0,
            p95Latency: 0,
            bucketInterval: '1m',
            throughputOverTime: [],
            levelDistribution: []
        };
        try {
            const params = new URLSearchParams();
            if (filters.service)
                params.append('service', filters.service);
            if (filters.from)
                params.append('from', filters.from);
            if (filters.to)
                params.append('to', filters.to);
            if (filters.timePreset)
                params.append('timePreset', filters.timePreset);
            const queryString = params.toString();
            const url = `${API_BASE_URL}/logs/metrics${queryString ? '?' + queryString : ''}`;
            const response = await fetch(url, SESSION_FETCH_OPTIONS);
            if (!response.ok) {
                console.warn(`Backend error (${response.status}) for ${url}, returning empty metrics.`);
                return empty;
            }
            const data = await response.json();
            return {
                totalLogs: Number(data?.totalLogs) || 0,
                errorCount: Number(data?.errorCount) || 0,
                errorRate: Number(data?.errorRate) || 0,
                avgResponseTime: Number(data?.avgResponseTime) || 0,
                p95Latency: Number(data?.p95Latency) || 0,
                bucketInterval: typeof data?.bucketInterval === 'string' ? data.bucketInterval : '1m',
                throughputOverTime: Array.isArray(data?.throughputOverTime)
                    ? data.throughputOverTime.map((point) => ({
                        time: String(point?.time || ''),
                        count: Number(point?.count) || 0,
                        intervalSeconds: Number(point?.intervalSeconds) || 60,
                        throughputPerSecond: Number(point?.throughputPerSecond) || 0,
                        errorCount: Number(point?.errorCount) || 0,
                        errorRate: Number(point?.errorRate) || 0,
                        avgResponseTime: Number(point?.avgResponseTime) || 0
                    }))
                    : [],
                levelDistribution: Array.isArray(data?.levelDistribution) ? data.levelDistribution : []
            };
        }
        catch (error) {
            console.error('Failed to fetch metrics:', error);
            return empty;
        }
    }
}
