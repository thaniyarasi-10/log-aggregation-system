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
}
