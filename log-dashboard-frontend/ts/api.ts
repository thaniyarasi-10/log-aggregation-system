import { LogEvent, FilterOptions, AlertsGrouped, MetricsResponse } from './types.js';

export type AdminUserView = {
    id: string;
    username: string;
    email: string;
    services: string[];
    roles: string[];
};

export type AdminServiceView = {
    id: string;
    name: string;
    description: string;
    active: boolean;
};

const API_BASE_URL = 'http://localhost:8080';

const SESSION_FETCH_OPTIONS: RequestInit = {
    credentials: 'include'
};

export class ApiClient {
    private static lastLogsFetchStatus: number | null = null;

    static getLastLogsFetchStatus(): number | null {
        return this.lastLogsFetchStatus;
    }

    static async fetchLogs(filters: FilterOptions): Promise<LogEvent[]> {
        try {
            const params = new URLSearchParams();
            if (filters.service) params.append('service', filters.service);
            if (filters.environment) params.append('environment', filters.environment);
            if (filters.level && filters.level.length > 0) {
                params.append('level', filters.level[0]);
            }
            if (filters.traceId) params.append('traceId', filters.traceId);
            if (filters.message) params.append('message', filters.message);
            if (filters.from) params.append('from', filters.from);
            if (filters.to) params.append('to', filters.to);
            if (typeof filters.page === 'number') params.append('page', String(filters.page));
            if (typeof filters.size === 'number') params.append('size', String(filters.size));

            const queryString = params.toString();
            const url = `${API_BASE_URL}/logs${queryString ? '?' + queryString : ''}`;

            const response = await fetch(url, SESSION_FETCH_OPTIONS);
            if (!response.ok) {
                this.lastLogsFetchStatus = response.status;
                console.warn(`Backend error (${response.status}) for ${url}, returning empty array.`);
                return [];
            }
            this.lastLogsFetchStatus = response.status;
            return await response.json();
        } catch (error) {
            this.lastLogsFetchStatus = -1;
            console.error("Failed to fetch logs:", error);
            return [];
        }
    }

    static async fetchAlerts(): Promise<AlertsGrouped> {
        try {
            const response = await fetch(`${API_BASE_URL}/alerts`, SESSION_FETCH_OPTIONS);

            if (!response.ok) {
                console.warn(`Backend error (${response.status}) for /alerts, returning empty alerts.`);
                return {};
            }

            return await response.json();
        } catch (error) {
            console.error('Failed to fetch alerts:', error);
            return {};
        }
    }

    static async fetchServices(filters: FilterOptions = {}): Promise<string[]> {
        try {
            const params = new URLSearchParams();
            if (filters.from) params.append('from', filters.from);
            if (filters.to) params.append('to', filters.to);
            if (typeof filters.size === 'number') params.append('size', String(filters.size));

            const queryString = params.toString();
            const url = `${API_BASE_URL}/logs/services${queryString ? '?' + queryString : ''}`;

            const response = await fetch(url, SESSION_FETCH_OPTIONS);

            if (!response.ok) {
                console.warn(`Backend error (${response.status}) for ${url}, returning empty service list.`);
                return [];
            }

            const data = await response.json();
            return Array.isArray(data)
                ? data.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
                : [];
        } catch (error) {
            console.error('Failed to fetch services:', error);
            return [];
        }
    }

    static async fetchMetrics(filters: FilterOptions): Promise<MetricsResponse> {
        const empty: MetricsResponse = {
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
            if (filters.service) params.append('service', filters.service);
            if (filters.from) params.append('from', filters.from);
            if (filters.to) params.append('to', filters.to);
            if (filters.timePreset) params.append('timePreset', filters.timePreset);

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
                    ? data.throughputOverTime.map((point: any) => ({
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
        } catch (error) {
            console.error('Failed to fetch metrics:', error);
            return empty;
        }
    }

    static async fetchAdminUsers(): Promise<AdminUserView[]> {
        try {
            const response = await fetch(`${API_BASE_URL}/api/admin/users`, SESSION_FETCH_OPTIONS);
            if (!response.ok) {
                return [];
            }

            const data = await response.json();
            return Array.isArray(data) ? data : [];
        } catch (error) {
            console.error('Failed to fetch admin users:', error);
            return [];
        }
    }

    static async updateAdminUser(
        userId: string,
        payload: { username: string; email: string; roles: string[]; services: string[] }
    ): Promise<{ ok: boolean; status: number; data?: AdminUserView; message?: string }> {
        try {
            const response = await fetch(`${API_BASE_URL}/api/admin/users/${encodeURIComponent(userId)}`, {
                ...SESSION_FETCH_OPTIONS,
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                const message = await response.text();
                return { ok: false, status: response.status, message };
            }

            const data = await response.json();
            return { ok: true, status: response.status, data };
        } catch (error) {
            console.error('Failed to update admin user:', error);
            return { ok: false, status: 500, message: 'Unable to update user' };
        }
    }

    static async deleteAdminUser(userId: string): Promise<{ ok: boolean; status: number; message?: string }> {
        try {
            const response = await fetch(`${API_BASE_URL}/api/admin/users/${encodeURIComponent(userId)}`, {
                ...SESSION_FETCH_OPTIONS,
                method: 'DELETE'
            });

            if (!response.ok) {
                const message = await response.text();
                return { ok: false, status: response.status, message };
            }

            return { ok: true, status: response.status };
        } catch (error) {
            console.error('Failed to delete admin user:', error);
            return { ok: false, status: 500, message: 'Unable to delete user' };
        }
    }

    static async fetchAdminServices(): Promise<AdminServiceView[]> {
        try {
            const response = await fetch(`${API_BASE_URL}/api/admin/services`, SESSION_FETCH_OPTIONS);
            if (!response.ok) {
                return [];
            }

            const data = await response.json();
            return Array.isArray(data) ? data : [];
        } catch (error) {
            console.error('Failed to fetch admin services:', error);
            return [];
        }
    }

    static async createService(name: string, description: string): Promise<{ ok: boolean; status: number; message?: string }> {
        try {
            const response = await fetch(`${API_BASE_URL}/api/admin/services`, {
                ...SESSION_FETCH_OPTIONS,
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ name, description })
            });

            if (!response.ok) {
                const message = await response.text();
                return { ok: false, status: response.status, message };
            }

            return { ok: true, status: response.status };
        } catch (error) {
            console.error('Failed to create service:', error);
            return { ok: false, status: 500, message: 'Unable to create service' };
        }
    }

    static async deleteService(serviceId: string): Promise<{ ok: boolean; status: number; message?: string }> {
        try {
            const response = await fetch(`${API_BASE_URL}/api/admin/services/${encodeURIComponent(serviceId)}`, {
                ...SESSION_FETCH_OPTIONS,
                method: 'DELETE'
            });

            if (!response.ok) {
                const message = await response.text();
                return { ok: false, status: response.status, message };
            }

            return { ok: true, status: response.status };
        } catch (error) {
            console.error('Failed to delete service:', error);
            return { ok: false, status: 500, message: 'Unable to delete service' };
        }
    }
}
