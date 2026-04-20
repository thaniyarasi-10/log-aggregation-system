import { useEffect, useMemo, useRef, useState } from 'react';
import { apiService, extractApiErrorMessage } from '../services/api';
import type { LogEvent, LogFilters } from '../types';

export function useRealtimeLogs(filters: LogFilters, enabled = true, intervalMs = 5000) {
  const [logs, setLogs] = useState<LogEvent[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>('');
  const lastStableLogsRef = useRef<LogEvent[]>([]);

  const filterKey = useMemo(() => JSON.stringify(filters), [filters]);

  useEffect(() => {
    if (!enabled) {
      return;
    }

    let active = true;
    let pollingTimer: number | null = null;

    const pullLogs = async () => {
      try {
        const data = await apiService.fetchLogs(filters);
        if (!active) return;

        if (Array.isArray(data) && data.length > 0) {
          lastStableLogsRef.current = data;
          setLogs(data);
          setError('');
          return;
        }

        if (!lastStableLogsRef.current.length) {
          setLogs([]);
        }
        setError('');
      } catch (err) {
        if (!active) return;
        const message = extractApiErrorMessage(err, 'Failed to load logs');
        setError(message);

        if (lastStableLogsRef.current.length) {
          setLogs(lastStableLogsRef.current);
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    };

    const startPolling = () => {
      if (pollingTimer !== null) {
        window.clearInterval(pollingTimer);
      }

      void pullLogs();
      pollingTimer = window.setInterval(() => {
        void pullLogs();
      }, intervalMs);
    };

    // Polling fallback for environments without websocket/SSE support.
    startPolling();

    return () => {
      active = false;
      if (pollingTimer !== null) {
        window.clearInterval(pollingTimer);
      }
    };
  }, [enabled, intervalMs, filterKey, filters]);

  return { logs, loading, error };
}
