import { useEffect, useMemo, useRef, useState } from 'react';
import LogsTable from '../components/LogsTable';
import MetricsCards from '../components/MetricsCards';
import MetricsCharts from '../components/MetricsCharts';
import SidebarFilters from '../components/SidebarFilters';
import { useRealtimeLogs } from '../hooks/useRealtimeLogs';
import { apiService } from '../services/api';
import type { LogEvent, LogFilters, MetricsResponse } from '../types';

const defaultFilters: LogFilters = {
  timeRange: '24h',
  service: '',
  level: '',
  search: ''
};

export default function LogsPage() {
  const [filters, setFilters] = useState<LogFilters>(defaultFilters);
  const [serviceOptions, setServiceOptions] = useState<string[]>([]);
  const [metrics, setMetrics] = useState<MetricsResponse>({
    totalLogs: 0,
    errorCount: 0,
    errorRate: 0,
    avgResponseTime: 0,
    p95Latency: 0,
    bucketInterval: '1m',
    throughputOverTime: [],
    levelDistribution: []
  });

  const lastStableMetricsRef = useRef<MetricsResponse | null>(null);

  const { logs, loading, error } = useRealtimeLogs(filters, true, 5000);

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

  useEffect(() => {
    let active = true;

    const refreshMetrics = async () => {
      const next = await apiService.fetchMetrics(filters);
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

      if (lastStableMetricsRef.current) {
        setMetrics(lastStableMetricsRef.current);
      } else {
        setMetrics(next);
      }
    };

    void refreshMetrics();
    const timer = window.setInterval(() => {
      void refreshMetrics();
    }, 3000);

    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [filters]);

  const sortedLogs = useMemo(() => {
    return [...logs].sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
  }, [logs]);

  const computedMetricsFromLogs = useMemo<MetricsResponse>(() => {
    const fallback = metrics;
    if (!sortedLogs.length) {
      return fallback;
    }

    const errors = sortedLogs.filter((log) => String(log.level).toUpperCase() === 'ERROR').length;
    const total = sortedLogs.length;
    const validLatency = sortedLogs
      .map((log) => Number(log.responseTime || 0))
      .filter((value) => Number.isFinite(value) && value > 0)
      .sort((a, b) => a - b);

    const avgResponseTime = validLatency.length
      ? validLatency.reduce((sum, value) => sum + value, 0) / validLatency.length
      : fallback.avgResponseTime;

    const p95Latency = validLatency.length
      ? validLatency[Math.max(0, Math.ceil(validLatency.length * 0.95) - 1)]
      : fallback.p95Latency;

    const firstTs = new Date(sortedLogs[0].timestamp).getTime();
    const lastTs = new Date(sortedLogs[sortedLogs.length - 1].timestamp).getTime();
    const spanSec = Math.max(1, (lastTs - firstTs) / 1000);
    const throughput = total / spanSec;

    const levelCount = new Map<string, number>();
    sortedLogs.forEach((log) => {
      const key = String(log.level || 'UNKNOWN').toUpperCase();
      levelCount.set(key, (levelCount.get(key) || 0) + 1);
    });

    const timeline = sortedLogs.map((log, index) => ({
      time: log.timestamp,
      count: 1,
      intervalSeconds: 1,
      throughputPerSecond: throughput,
      errorCount: String(log.level).toUpperCase() === 'ERROR' ? 1 : 0,
      errorRate: total ? (errors / total) * 100 : 0,
      avgResponseTime: Number(log.responseTime || 0) || avgResponseTime
    }));

    return {
      totalLogs: total,
      errorCount: errors,
      errorRate: total ? (errors / total) * 100 : 0,
      avgResponseTime,
      p95Latency,
      bucketInterval: '1s',
      throughputOverTime: timeline,
      levelDistribution: Array.from(levelCount.entries()).map(([level, count]) => ({ level, count }))
    };
  }, [metrics, sortedLogs]);

  return (
    <section className="dashboard-grid">
      <SidebarFilters filters={filters} services={serviceOptions} onChange={setFilters} />
      <section className="dashboard-main">
        <MetricsCards metrics={computedMetricsFromLogs} />
        <MetricsCharts metrics={computedMetricsFromLogs} />
        <LogsTable logs={sortedLogs as LogEvent[]} loading={loading} error={error} searchTerm={filters.search} />
      </section>
    </section>
  );
}
