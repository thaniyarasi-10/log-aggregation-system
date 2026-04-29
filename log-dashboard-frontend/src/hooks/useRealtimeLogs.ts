import { useEffect, useMemo, useRef, useState } from 'react';
import { apiService, extractApiErrorMessage } from '../services/api';
import type { LogEvent, LogFilters } from '../types';
import { RANGE_TO_MS } from '../utils/time';

export function useRealtimeLogs(filters: LogFilters, enabled = true, intervalMs = 30000) {
  const [logs, setLogs] = useState<LogEvent[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>('');
  const lastStableLogsRef = useRef<LogEvent[]>([]);
  const filtersRef = useRef<LogFilters>(filters);
  const wsConnectedRef = useRef<boolean>(false);

  const filterKey = useMemo(() => JSON.stringify(filters), [filters]);

  // Keep filtersRef in sync with current filters for WebSocket handlers
  useEffect(() => {
    filtersRef.current = filters;
  }, [filters]);

  useEffect(() => {
    if (!enabled) {
      return;
    }

    let active = true;
    let pollingTimer: number | null = null;
    let socket: WebSocket | null = null;

    // Reset state when filters change
    lastStableLogsRef.current = [];
    setLogs([]);

    const buildWsUrl = () => {
      const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
      return `${protocol}://${window.location.host}/ws`;
    };

    const sendStompFrame = (command: string, headers: Record<string, string> = {}, body = '') => {
      if (!socket || socket.readyState !== WebSocket.OPEN) {
        return;
      }

      const headerLines = Object.entries(headers).map(([key, value]) => `${key}:${value}`);
      const frame = [command, ...headerLines, '', body].join('\n') + '\0';
      socket.send(frame);
    };

    const parseStompFrames = (raw: string) => raw.split('\0').map((frame) => frame.trim()).filter(Boolean);

    const parseMessageBody = (frame: string) => {
      const delimiter = '\n\n';
      const idx = frame.indexOf(delimiter);
      if (idx < 0) {
        return '';
      }
      return frame.slice(idx + delimiter.length);
    };

    const normalize = (value: string) => value.toLowerCase().trim();

    const withinTimeRange = (timestamp: string) => {
      const rangeMs = RANGE_TO_MS[filtersRef.current.timeRange] ?? RANGE_TO_MS['15m'];
      const eventTs = new Date(timestamp).getTime();
      if (!Number.isFinite(eventTs)) {
        return true;
      }
      return eventTs >= Date.now() - rangeMs;
    };

    const matchesFilters = (event: LogEvent) => {
      const currentFilters = filtersRef.current;
      if (currentFilters.service && normalize(event.service || '') !== normalize(currentFilters.service)) {
        return false;
      }
      if (currentFilters.level && normalize(String(event.level || '')) !== normalize(currentFilters.level)) {
        return false;
      }
      if (currentFilters.search && !normalize(event.message || '').includes(normalize(currentFilters.search))) {
        return false;
      }
      return withinTimeRange(event.timestamp);
    };

    const eventKey = (event: LogEvent) => {
      return `${event.timestamp}|${event.service}|${event.level}|${event.traceId || ''}|${event.message}`;
    };

    const connectRealtime = () => {
      try {
        socket = new WebSocket(buildWsUrl());

        socket.onopen = () => {
          wsConnectedRef.current = true;
          sendStompFrame('CONNECT', {
            'accept-version': '1.2',
            'heart-beat': '10000,10000'
          });
        };

        socket.onmessage = (evt) => {
          if (!active) {
            return;
          }

          const frames = parseStompFrames(String(evt.data || ''));
          for (const frame of frames) {
            if (frame.startsWith('CONNECTED')) {
              sendStompFrame('SUBSCRIBE', {
                id: 'logs-subscription',
                destination: '/topic/logs',
                ack: 'auto'
              });
              continue;
            }

            if (!frame.startsWith('MESSAGE')) {
              continue;
            }

            const body = parseMessageBody(frame);
            if (!body) {
              continue;
            }

            try {
              const incoming = JSON.parse(body) as LogEvent;
              if (!incoming || !matchesFilters(incoming)) {
                continue;
              }

              setLogs((prev) => {
                const key = eventKey(incoming);
                if (prev.some((item) => eventKey(item) === key)) {
                  return prev;
                }

                const next = [...prev, incoming];
                lastStableLogsRef.current = next;
                return next;
              });
              setError('');
            } catch {
              // Ignore malformed websocket messages and continue polling fallback.
            }
          }
        };

        socket.onclose = () => {
          wsConnectedRef.current = false;
          socket = null;
        };
      } catch {
        socket = null;
      }
    };

    const pullLogs = async () => {
      try {
        const data = await apiService.fetchLogs(filtersRef.current);
        if (!active) return;

        if (Array.isArray(data) && data.length > 0) {
          lastStableLogsRef.current = data;
          setLogs(data);
          setError('');
          return;
        }

        // No data returned — show empty state
        setLogs([]);
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
        // Only poll if WebSocket is not connected
        if (!wsConnectedRef.current) {
          void pullLogs();
        }
      }, intervalMs);
    };

    // Polling fallback for environments without websocket/SSE support.
    connectRealtime();
    startPolling();

    return () => {
      active = false;
      if (pollingTimer !== null) {
        window.clearInterval(pollingTimer);
      }
      if (socket && socket.readyState === WebSocket.OPEN) {
        sendStompFrame('DISCONNECT');
        socket.close();
      }
    };
  }, [enabled, intervalMs, filterKey, filters]);

  return { logs, loading, error };
}
