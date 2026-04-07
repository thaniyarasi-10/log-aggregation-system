import { FilterOptions, LogEvent } from './types.js';
import { ApiClient } from './api.js';

export class RealtimeManager {
    private intervalId: number | null = null;
    private filters: FilterOptions = {};
    private readonly onDataCallback: (logs: LogEvent[]) => void;
    private isFetching: boolean = false;
    private lastTimestamp: string | null = null;

    constructor(onData: (logs: LogEvent[]) => void) {
        this.onDataCallback = onData;
    }

    public setFilters(
        filters: FilterOptions,
        options: { resetCursor?: boolean; immediateFetch?: boolean } = {}
    ) {
        const { resetCursor = true, immediateFetch = true } = options;
        this.filters = filters;
        if (resetCursor) {
            this.lastTimestamp = null;
        }
        if (immediateFetch) {
            this.triggerFetch();
        }
    }

    public seedCursorFromLogs(logs: LogEvent[]) {
        const latestMs = this.getLatestTimestampMs(logs);
        if (latestMs !== null) {
            this.lastTimestamp = new Date(latestMs + 1).toISOString();
        }
    }

    public startPooling(intervalMs: number = 2000) {
        this.stopPooling();
        this.triggerFetch();
        this.intervalId = globalThis.setInterval(() => this.triggerFetch(), intervalMs);
    }

    public stopPooling() {
        if (this.intervalId !== null) {
            globalThis.clearInterval(this.intervalId);
            this.intervalId = null;
        }
    }

    private async triggerFetch() {
        if (this.isFetching) return;
        this.isFetching = true;
        try {
            const currentFilters = { ...this.filters };

            if (currentFilters.timePreset && currentFilters.timePreset !== 'all' && currentFilters.timePreset !== 'custom') {
                const presetDurationsMs: Record<string, number> = {
                    '5m': 5 * 60 * 1000,
                    '15m': 15 * 60 * 1000,
                    '1h': 60 * 60 * 1000,
                    '24h': 24 * 60 * 60 * 1000
                };

                const durationMs = presetDurationsMs[currentFilters.timePreset];
                if (durationMs) {
                    const now = new Date();
                    currentFilters.to = now.toISOString();
                    currentFilters.from = new Date(now.getTime() - durationMs).toISOString();
                }
            }

            // DO NOT filter by level on the backend. This allows the frontend to calculate genuine total error rates and level distributions spanning ALL levels.
            // delete currentFilters.level;

            if (this.lastTimestamp) {
                 currentFilters.from = this.lastTimestamp;
            }

              // Request a larger incremental batch so noisy services don't starve quieter ones.
              currentFilters.size = 300;

            // Using ApiClient which hits /logs
            const logs = await ApiClient.fetchLogs(currentFilters);
            if (logs && logs.length > 0) {
                const normalized = this.normalizeLogs(logs);

                // Determine the most recent valid timestamp to avoid duplicates.
                const latestMs = this.getLatestTimestampMs(normalized);
                if (latestMs !== null) {
                    this.lastTimestamp = new Date(latestMs + 1).toISOString();
                }

                // Sort logs chronologically before feeding to callback
                normalized.sort((a, b) => {
                    const aMs = this.toTimestampMs(a.timestamp);
                    const bMs = this.toTimestampMs(b.timestamp);
                    if (aMs === null && bMs === null) return 0;
                    if (aMs === null) return 1;
                    if (bMs === null) return -1;
                    return aMs - bMs;
                });
                this.onDataCallback(normalized);
            }
        } catch (err) {
            console.error("Polling error:", err);
        } finally {
            this.isFetching = false;
        }
    }

    private normalizeLogs(logs: LogEvent[]): LogEvent[] {
        return logs.map(log => {
            const ts = this.toTimestampMs(log.timestamp);
            if (ts !== null) {
                return log;
            }

            return {
                ...log,
                timestamp: new Date().toISOString()
            };
        });
    }

    private getLatestTimestampMs(logs: LogEvent[]): number | null {
        const allTimes = logs
            .map(log => this.toTimestampMs(log.timestamp))
            .filter((ms): ms is number => ms !== null);

        if (!allTimes.length) return null;
        return Math.max(...allTimes);
    }

    private toTimestampMs(timestamp: string | undefined): number | null {
        if (!timestamp) return null;
        const ms = new Date(timestamp).getTime();
        return Number.isFinite(ms) ? ms : null;
    }
}
