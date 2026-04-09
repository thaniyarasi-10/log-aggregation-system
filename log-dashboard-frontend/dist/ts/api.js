// Central helper to get auth headers for every backend request
function getAuthHeaders() {
    const token = localStorage.getItem('logflow_jwt');
    return token ? { 'Authorization': 'Bearer ' + token } : {};
}
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
            const url = `http://localhost:8080/logs${queryString ? '?' + queryString : ''}`;
            const response = await fetch(url, {
                headers: getAuthHeaders()
            });
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
            const response = await fetch('http://localhost:8080/alerts', {
                headers: getAuthHeaders()
            });
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
            const params = new URLSearchParams();
            if (filters.from)
                params.append('from', filters.from);
            if (filters.to)
                params.append('to', filters.to);
            const requestedSize = typeof filters.size === 'number' ? filters.size : 5000;
            params.append('size', String(requestedSize));
            const queryString = params.toString();
            const url = `http://localhost:8080/logs/services${queryString ? '?' + queryString : ''}`;
            const response = await fetch(url, {
                headers: getAuthHeaders()
            });
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
            const url = `http://localhost:8080/logs/metrics${queryString ? '?' + queryString : ''}`;
            const response = await fetch(url, {
                headers: getAuthHeaders()
            });
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
