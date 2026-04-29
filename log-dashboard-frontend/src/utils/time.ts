import type { LogFilters, LogQueryParams } from '../types';

export const RANGE_TO_MS: Record<LogFilters['timeRange'], number> = {
  '5m': 5 * 60 * 1000,
  '15m': 15 * 60 * 1000,
  '1h': 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
  '15d': 15 * 24 * 60 * 60 * 1000
};

export function buildLogQueryParams(filters: LogFilters): LogQueryParams {
  const now = Date.now();
  const rangeMs = RANGE_TO_MS[filters.timeRange] ?? RANGE_TO_MS['15m'];
  const fromDate = new Date(now - rangeMs);
  const toDate = new Date(now);

  const isValidDate = (value: Date) => Number.isFinite(value.getTime());
  const from = isValidDate(fromDate) ? fromDate.toISOString() : new Date(now - RANGE_TO_MS['15m']).toISOString();
  const to = isValidDate(toDate) ? toDate.toISOString() : new Date(now).toISOString();

  const params: LogQueryParams = {
    from,
    to,
    timePreset: filters.timeRange || '15m',
    page: 0,
    size: 500
  };

  // Map filters to backend query parameters
  if (filters.service && filters.service.trim()) {
    params.service = filters.service.trim();
  }
  if (filters.level && filters.level.trim()) {
    params.level = filters.level.trim();
  }
  if (filters.search && filters.search.trim()) {
    params.message = filters.search.trim();
  }

  return params;
}
