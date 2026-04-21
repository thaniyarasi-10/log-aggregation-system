import { useEffect, useMemo, useRef, useState } from 'react';
import { apiService, extractApiErrorMessage } from '../services/api';
import type { LogEvent, LogFilters } from '../types';
import { RANGE_TO_MS } from '../utils/time';

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
    let socket: WebSocket | null = null;

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
      const rangeMs = RANGE_TO_MS[filters.timeRange] ?? RANGE_TO_MS['15m'];
      const eventTs = new Date(timestamp).getTime();
      if (!Number.isFinite(eventTs)) {
        return true;
      }
      return eventTs >= Date.now() - rangeMs;
    };

    const matchesFilters = (event: LogEvent) => {
      if (filters.service && normalize(event.service || '') !== normalize(filters.service)) {
        return false;
      }
      if (filters.level && normalize(String(event.level || '')) !== normalize(filters.level)) {
        return false;
      }
      if (filters.search && !normalize(event.message || '').includes(normalize(filters.search))) {
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
          socket = null;
        };
      } catch {
        socket = null;
      }
    };

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
