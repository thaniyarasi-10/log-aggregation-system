import { FilterOptions, LogEvent } from './types.js';
import { ApiClient } from './api.js';

export class RealtimeManager {
    private static readonly FULL_REFRESH_EVERY_MS = 30000;
    private intervalId: number | null = null;
    private filters: FilterOptions = {};
    private readonly onDataCallback: (logs: LogEvent[]) => void;
    private isFetching: boolean = false;
    private lastTimestamp: string | null = null;
    private readonly seenLogKeys = new Set<string>();
    private readonly seenLogQueue: string[] = [];
    private readonly maxSeenLogKeys = 10000;
    private lastFullFetchAt: number = 0;

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
            this.seenLogKeys.clear();
            this.seenLogQueue.length = 0;
            this.lastFullFetchAt = 0;
        }
        if (immediateFetch) {
            this.triggerFetch();
        }
    }

    public seedCursorFromLogs(logs: LogEvent[]) {
        const latestMs = this.getLatestTimestampMs(logs);
        if (latestMs !== null) {
            this.lastTimestamp = new Date(latestMs).toISOString();
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

            if (currentFilters.timePreset && currentFilters.timePreset !== 'custom') {
                const presetDurationsMs: Record<string, number> = {
                    '5m': 5 * 60 * 1000,
                    '15m': 15 * 60 * 1000,
                    '1h': 60 * 60 * 1000,
                    '24h': 24 * 60 * 60 * 1000,
                    '7d': 7 * 24 * 60 * 60 * 1000,
                    '15d': 15 * 24 * 60 * 60 * 1000
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

                        const nowMs = Date.now();
                        const shouldRunFullWindowRefresh = nowMs - this.lastFullFetchAt >= RealtimeManager.FULL_REFRESH_EVERY_MS;

                            if (this.lastTimestamp && !shouldRunFullWindowRefresh) {
                  const lastMs = this.toTimestampMs(this.lastTimestamp);
                  if (lastMs !== null) {
                    currentFilters.from = new Date(Math.max(0, lastMs - 1000)).toISOString();
                  }
            }

                        if (shouldRunFullWindowRefresh) {
                                this.lastFullFetchAt = nowMs;
                        }

              // Request a larger incremental batch so noisy services don't starve quieter ones.
                            currentFilters.size = shouldRunFullWindowRefresh ? 1000 : 300;

            // Using ApiClient which hits /logs
            const logs = await ApiClient.fetchLogs(currentFilters);
            if (logs && logs.length > 0) {
                const normalized = this.normalizeLogs(logs);
                const deduplicated = normalized.filter(log => this.markIfNew(log));
                if (!deduplicated.length) {
                    return;
                }

                // Determine the most recent valid timestamp to avoid duplicates.
                const latestMs = this.getLatestTimestampMs(deduplicated);
                if (latestMs !== null) {
                    this.lastTimestamp = new Date(latestMs).toISOString();
                }

                // Sort logs chronologically before feeding to callback
                deduplicated.sort((a, b) => {
                    const aMs = this.toTimestampMs(a.timestamp);
                    const bMs = this.toTimestampMs(b.timestamp);
                    if (aMs === null && bMs === null) return 0;
                    if (aMs === null) return 1;
                    if (bMs === null) return -1;
                    return aMs - bMs;
                });
                this.onDataCallback(deduplicated);
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

    private markIfNew(log: LogEvent): boolean {
        const key = `${log.timestamp}|${log.service}|${log.level}|${log.traceId || ''}|${log.instance || ''}|${log.message}`;
        if (this.seenLogKeys.has(key)) {
            return false;
        }

        this.seenLogKeys.add(key);
        this.seenLogQueue.push(key);

        if (this.seenLogQueue.length > this.maxSeenLogKeys) {
            const oldest = this.seenLogQueue.shift();
            if (oldest) {
                this.seenLogKeys.delete(oldest);
            }
        }

        return true;
    }
}
