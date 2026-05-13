import { useEffect, useMemo, useRef, useState } from 'react';
import LogsTable from '../components/LogsTable';
import MetricsCards from '../components/MetricsCards';
import MetricsCharts from '../components/MetricsCharts';
import SidebarFilters from '../components/SidebarFilters';
import { useRealtimeLogs } from '../hooks/useRealtimeLogs';
import { useAuth } from '../context/AuthContext';
import { apiService } from '../services/api';
import type { LogEvent, LogFilters, MetricsResponse } from '../types';

const defaultFilters: LogFilters = {
  timeRange: '24h',
  services: [],
  levels: [],
  search: '',
  // Legacy aliases — kept in sync by SidebarFilters
  service: '',
  level: '',
};

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

export default function LogsPage() {
  const { isAdmin, user } = useAuth();
  const [filters, setFilters] = useState<LogFilters>(defaultFilters);
  const [serviceOptions, setServiceOptions] = useState<string[]>([]);
  const [metrics, setMetrics] = useState<MetricsResponse>(emptyMetrics);

  const lastStableMetricsRef = useRef<MetricsResponse | null>(null);

  // Derive the user's allowed services from the auth context.
  // These are passed to useRealtimeLogs for the client-side RBAC guard.
  const allowedServices = useMemo(
    () => Array.isArray(user?.allowedServices) ? user.allowedServices : [],
    [user?.allowedServices]
  );

  // Metrics only depend on service + timeRange — level and search are intentionally excluded.
  // For multi-select, join services into a single string for the metrics key.
  const metricsKey = `${filters.services.join(',')}|${filters.timeRange}`;

  // Logs react to all filters (service, timeRange, level, search).
  // allowedServices and isAdmin are passed so the hook can enforce the RBAC
  // service guard on incoming WebSocket messages as a second line of defence.
  const { logs, loading, error } = useRealtimeLogs(filters, true, 5000, allowedServices, isAdmin);

  // Load the service dropdown once on mount.
  // The backend /logs/services endpoint already scopes the list to the user's allowed services,
  // so admins see all services and devs see only their mapped ones.
  useEffect(() => {
    let active = true;

    const loadServices = async () => {
      try {
        const services = await apiService.fetchServices();
        if (!active) return;

        const names = services
          .map((item) => item.name)
          .filter((name): name is string => typeof name === 'string' && name.trim().length > 0)
          .sort((a, b) => a.localeCompare(b));

        setServiceOptions(names);
      } catch {
        if (active) {
          setServiceOptions([]);
        }
      }
    };

    void loadServices();

    return () => {
      active = false;
    };
  }, []);

  // Fetch metrics from the API whenever service or timeRange changes.
  // Level and search are deliberately NOT included — metrics must ignore those filters.
  // The backend /logs/metrics endpoint accepts only: service, from, to, timePreset.
  // When service is '' (All Services), the backend aggregates across all services the
  // authenticated user is allowed to access (admin = all, dev = mapped services only).
  useEffect(() => {
    let active = true;

    // Build a metrics-only filter: strip levels and search so they are never sent
    const metricsFilters: LogFilters = {
      timeRange: filters.timeRange,
      services: filters.services,
      levels: [],
      search: '',
      service: filters.services.length === 1 ? filters.services[0] : '',
      level: '',
    };

    const refreshMetrics = async () => {
      const next = await apiService.fetchMetrics(metricsFilters);
      if (!active) return;

      const hasSignal =
        next.totalLogs > 0
        || next.errorCount > 0
        || next.avgResponseTime > 0
        || next.p95Latency > 0
        || next.throughputOverTime.length > 0
        || next.levelDistribution.length > 0;

      if (hasSignal) {
        lastStableMetricsRef.current = next;
        setMetrics(next);
        return;
      }

      // Backend returned zeros — keep the last known good data to avoid flickering,
      // but only if the service/time scope hasn't changed since that data was fetched.
      if (lastStableMetricsRef.current) {
        setMetrics(lastStableMetricsRef.current);
      } else {
        setMetrics(next);
      }
    };

    // Reset stable cache immediately when scope changes so stale data from a
    // different service/time range is never shown for the new selection.
    lastStableMetricsRef.current = null;
    setMetrics(emptyMetrics);

    void refreshMetrics();
    const timer = window.setInterval(() => {
      void refreshMetrics();
    }, 10000);

    return () => {
      active = false;
      window.clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [metricsKey]); // only re-run when service or timeRange changes

  // Sort logs for the table — newest first. Purely presentational; does not affect metrics.
  // Capped at 500 entries to keep the DOM lean while still showing a deep history.
  const MAX_DISPLAY_LOGS = 500;
  const sortedLogs = useMemo(() => {
    const sorted = [...logs].sort(
      (a, b) =>
        new Date(b["@timestamp"] ?? 0).getTime() - new Date(a["@timestamp"] ?? 0).getTime()
    );
    return sorted.slice(0, MAX_DISPLAY_LOGS);
  }, [logs]);

  return (
    <section className="dashboard-grid">
      <SidebarFilters filters={filters} services={serviceOptions} onChange={setFilters} />
      <section className="dashboard-main">
        {/* Analytics zone: fixed-height band — cards + charts */}
        <div className="dashboard-analytics">
          <MetricsCards metrics={metrics} />
          <MetricsCharts metrics={metrics} />
        </div>
        {/* Logs zone: fills all remaining viewport height */}
        <div className="dashboard-logs">
          <LogsTable logs={sortedLogs as LogEvent[]} loading={loading} error={error} searchTerm={filters.search} />
        </div>
      </section>
    </section>
  );
}
