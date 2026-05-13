import { useEffect, useMemo, useRef, useState } from 'react';
import { apiService, extractApiErrorMessage } from '../services/api';
import type { LogEvent, LogFilters } from '../types';
import { RANGE_TO_MS } from '../utils/time';

/**
 * Realtime log streaming hook.
 *
 * Transport: STOMP over WebSocket, subscribing to the user-specific destination
 * /user/queue/logs. The backend delivers only logs the authenticated user is
 * authorised to see (ADMIN = all services, DEV = mapped services only).
 *
 * The frontend adds a second layer of defence in matchesFilters: if the server
 * somehow delivers a log for a service not in the user's allowedServices list,
 * it is silently dropped before reaching the UI.
 *
 * Falls back to HTTP polling when the WebSocket connection is unavailable.
 * Automatically reconnects the WebSocket if it drops.
 *
 * KEY DESIGN DECISIONS:
 * - Filters are kept in a ref so the WebSocket handler always sees the latest
 *   values WITHOUT needing to reconnect. Reconnecting on every filter change
 *   would wipe accumulated realtime logs and cause a visible flash.
 * - HTTP polling MERGES results with existing logs rather than replacing them,
 *   so WS-received logs are never lost when a poll fires.
 * - Duplicate detection uses a composite key so the same event is never shown twice.
 */
export function useRealtimeLogs(
  filters: LogFilters,
  enabled = true,
  intervalMs = 30000,
  /** Lowercase set of service names the user is allowed to see. Empty = no restriction (admin). */
  allowedServices: string[] = [],
  isAdmin = false,
) {
  const [logs, setLogs] = useState<LogEvent[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>('');

  // ── Stable refs — updated on every render but never trigger re-effects ──────
  const filtersRef = useRef<LogFilters>(filters);
  const allowedServicesRef = useRef<Set<string>>(new Set());
  const isAdminRef = useRef<boolean>(isAdmin);
  const seenKeysRef = useRef<Set<string>>(new Set());

  // WebSocket state refs — shared between the effect closure and reconnect timer
  const wsConnectedRef = useRef<boolean>(false);
  const stompSubscribedRef = useRef<boolean>(false);
  const socketRef = useRef<WebSocket | null>(null);

  // Keep all refs in sync with latest props on every render
  useEffect(() => {
    filtersRef.current = filters;
  }, [filters]);

  useEffect(() => {
    allowedServicesRef.current = new Set(
      allowedServices.map((s) => s.trim().toLowerCase()).filter(Boolean)
    );
    isAdminRef.current = isAdmin;
  }, [allowedServices, isAdmin]);

  // ── Main effect — runs once on mount (and on enabled/intervalMs change) ─────
  // Filters are intentionally NOT in the dependency array. The filtersRef keeps
  // them current so the WS handler always applies the latest filter values without
  // tearing down and rebuilding the socket on every keystroke.
  useEffect(() => {
    if (!enabled) {
      return;
    }

    let active = true;
    let pollingTimer: number | null = null;
    let reconnectTimer: number | null = null;

    // ── STOMP helpers ──────────────────────────────────────────────────────────

    const buildWsUrl = () => {
      const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
      return `${protocol}://${window.location.host}/ws`;
    };

    const sendStompFrame = (command: string, headers: Record<string, string> = {}, body = '') => {
      const sock = socketRef.current;
      if (!sock || sock.readyState !== WebSocket.OPEN) {
        return;
      }
      const headerLines = Object.entries(headers).map(([key, value]) => `${key}:${value}`);
      const frame = [command, ...headerLines, '', body].join('\n') + '\0';
      sock.send(frame);
    };

    const parseStompFrames = (raw: string) =>
      raw.split('\0').map((frame) => frame.trim()).filter(Boolean);

    const parseMessageBody = (frame: string) => {
      const delimiter = '\n\n';
      const idx = frame.indexOf(delimiter);
      if (idx < 0) return '';
      return frame.slice(idx + delimiter.length);
    };

    // ── Filter helpers ─────────────────────────────────────────────────────────

    const normalize = (value: string) => value.toLowerCase().trim();

    const withinTimeRange = (timestamp: string | undefined) => {
      const rangeMs = RANGE_TO_MS[filtersRef.current.timeRange] ?? RANGE_TO_MS['15m'];
      if (!timestamp) return true;
      const eventTs = new Date(timestamp).getTime();
      if (!Number.isFinite(eventTs)) return true;
      return eventTs >= Date.now() - rangeMs;
    };

    /**
     * Returns true if the event should be shown given the current filters.
     * Uses filtersRef so it always reflects the latest filter state without
     * needing to recreate the WebSocket handler.
     */
    const matchesFilters = (event: LogEvent): boolean => {
      const currentFilters = filtersRef.current;

      // ── RBAC service guard (defence-in-depth) ──────────────────────────────
      if (!isAdminRef.current) {
        const allowed = allowedServicesRef.current;
        if (allowed.size > 0) {
          const eventService = normalize(event.service || '');
          if (eventService && !allowed.has(eventService)) {
            console.debug('[WS] RBAC drop — service not in allowedServices:', event.service);
            return false;
          }
        }
      }

      // ── UI filter checks ───────────────────────────────────────────────────
      // Multi-select: empty array = no filter (show all)
      const selectedServices = currentFilters.services ?? [];
      if (selectedServices.length > 0) {
        const eventService = normalize(event.service || '');
        if (!selectedServices.some((s) => normalize(s) === eventService)) {
          return false;
        }
      }

      const selectedLevels = currentFilters.levels ?? [];
      if (selectedLevels.length > 0) {
        const eventLevel = normalize(String(event.level || ''));
        if (!selectedLevels.some((l) => normalize(l) === eventLevel)) {
          return false;
        }
      }

      if (currentFilters.search && !normalize(event.message || '').includes(normalize(currentFilters.search))) {
        return false;
      }
      return withinTimeRange(event['@timestamp']);
    };

    const eventKey = (event: LogEvent) =>
      `${event['@timestamp'] ?? ''}|${event.service ?? ''}|${event.level ?? ''}|${event.traceId ?? ''}|${event.message ?? ''}`;

    // ── WebSocket / STOMP connection ───────────────────────────────────────────

    const connectRealtime = () => {
      // Don't open a second socket if one is already open or connecting
      if (
        socketRef.current &&
        (socketRef.current.readyState === WebSocket.OPEN ||
          socketRef.current.readyState === WebSocket.CONNECTING)
      ) {
        return;
      }

      console.debug('[WS] Connecting to', buildWsUrl());

      let sock: WebSocket;
      try {
        sock = new WebSocket(buildWsUrl());
      } catch (err) {
        console.warn('[WS] Failed to create WebSocket:', err);
        return;
      }

      socketRef.current = sock;

      sock.onopen = () => {
        if (!active) return;
        console.debug('[WS] Connection open — sending STOMP CONNECT');
        wsConnectedRef.current = true;
        stompSubscribedRef.current = false;
        sendStompFrame('CONNECT', {
          'accept-version': '1.2',
          'heart-beat': '10000,10000',
        });
      };

      sock.onmessage = (evt) => {
        if (!active) return;

        const frames = parseStompFrames(String(evt.data || ''));
        for (const frame of frames) {
          // ── STOMP CONNECTED — now subscribe ──────────────────────────────
          if (frame.startsWith('CONNECTED')) {
            console.debug('[WS] STOMP CONNECTED — subscribing to /user/queue/logs');
            stompSubscribedRef.current = true;
            sendStompFrame('SUBSCRIBE', {
              id: 'logs-subscription',
              destination: '/user/queue/logs',
              ack: 'auto',
            });
            continue;
          }

          if (!frame.startsWith('MESSAGE')) continue;

          const body = parseMessageBody(frame);
          if (!body) continue;

          try {
            const incoming = JSON.parse(body) as LogEvent;
            console.debug('[WS] Message received — service:', incoming?.service, 'level:', incoming?.level);

            if (!incoming) continue;

            if (!matchesFilters(incoming)) {
              console.debug('[WS] Message filtered out by current filters');
              continue;
            }

            const key = eventKey(incoming);

            // Deduplicate before touching state
            if (seenKeysRef.current.has(key)) {
              console.debug('[WS] Duplicate message dropped');
              continue;
            }
            seenKeysRef.current.add(key);

            // Prepend so newest logs appear at the top immediately.
            // Using functional update avoids stale closure issues.
            setLogs((prev) => {
              // Double-check against current state in case the ref was stale
              if (prev.some((item) => eventKey(item) === key)) return prev;
              console.debug('[WS] State updated — total logs:', prev.length + 1);
              return [incoming, ...prev];
            });

            setError('');
          } catch {
            // Ignore malformed WebSocket messages; polling fallback will cover gaps.
          }
        }
      };

      sock.onclose = (evt) => {
        console.debug('[WS] Connection closed — code:', evt.code, 'reason:', evt.reason);
        wsConnectedRef.current = false;
        stompSubscribedRef.current = false;
        if (socketRef.current === sock) {
          socketRef.current = null;
        }

        // Schedule reconnect after 3 seconds if the effect is still active
        if (active) {
          console.debug('[WS] Scheduling reconnect in 3s');
          reconnectTimer = window.setTimeout(() => {
            if (active) {
              console.debug('[WS] Attempting reconnect');
              connectRealtime();
            }
          }, 3000);
        }
      };

      sock.onerror = (err) => {
        console.warn('[WS] WebSocket error:', err);
        // onclose will fire after onerror — reconnect is handled there
      };
    };

    // ── HTTP polling — merges with existing logs, never replaces ──────────────

    const pullLogs = async () => {
      try {
        const data = await apiService.fetchLogs(filtersRef.current);
        if (!active) return;

        if (!Array.isArray(data) || data.length === 0) {
          setLoading(false);
          return;
        }

        setLogs((prev) => {
          // Merge: add any polled logs not already in state (by key)
          const existingKeys = new Set(prev.map(eventKey));
          const newEntries = data.filter((e) => {
            const k = eventKey(e);
            if (existingKeys.has(k)) return false;
            // Also register in the global seen-keys set so WS doesn't re-add them
            seenKeysRef.current.add(k);
            return true;
          });

          if (newEntries.length === 0) return prev;

          console.debug('[POLL] Merging', newEntries.length, 'new log(s) from HTTP poll');
          // Combine and let LogsPage sort by timestamp
          return [...prev, ...newEntries];
        });

        setError('');
      } catch (err) {
        if (!active) return;
        const message = extractApiErrorMessage(err, 'Failed to load logs');
        setError(message);
      } finally {
        if (active) setLoading(false);
      }
    };

    const startPolling = () => {
      if (pollingTimer !== null) window.clearInterval(pollingTimer);
      // Initial fetch to populate the table immediately
      void pullLogs();
      pollingTimer = window.setInterval(() => {
        // Always poll — it merges safely even when WS is active
        void pullLogs();
      }, intervalMs);
    };

    // ── Boot ───────────────────────────────────────────────────────────────────
    connectRealtime();
    startPolling();

    return () => {
      active = false;
      if (pollingTimer !== null) window.clearInterval(pollingTimer);
      if (reconnectTimer !== null) window.clearTimeout(reconnectTimer);
      const sock = socketRef.current;
      if (sock && sock.readyState === WebSocket.OPEN) {
        sendStompFrame('DISCONNECT');
        sock.close();
      }
      socketRef.current = null;
      wsConnectedRef.current = false;
      stompSubscribedRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, intervalMs]); // Filters intentionally excluded — handled via filtersRef

  // ── Filter change: re-apply filters to existing logs and reset seen-keys ────
  // When filters change we don't reconnect the socket. Instead we:
  //   1. Clear the seen-keys set so the next poll can re-populate with filtered results.
  //   2. Wipe the log list so stale out-of-filter logs don't linger.
  //   3. Trigger a fresh HTTP poll immediately.
  const filterKey = useMemo(() => JSON.stringify(filters), [filters]);

  useEffect(() => {
    // Don't run on initial mount — the main effect handles the first load
    seenKeysRef.current = new Set();
    setLogs([]);
    setLoading(true);

    let active = true;
    apiService.fetchLogs(filtersRef.current).then((data) => {
      if (!active) return;
      if (Array.isArray(data) && data.length > 0) {
        data.forEach((e) => seenKeysRef.current.add(eventKey(e)));
        setLogs(data);
      }
      setError('');
    }).catch((err) => {
      if (!active) return;
      setError(extractApiErrorMessage(err, 'Failed to load logs'));
    }).finally(() => {
      if (active) setLoading(false);
    });

    return () => { active = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey]);

  return { logs, loading, error };
}

// Helper used outside the effect (needs to be module-level for the filterKey effect)
function eventKey(event: LogEvent) {
  return `${event['@timestamp'] ?? ''}|${event.service ?? ''}|${event.level ?? ''}|${event.traceId ?? ''}|${event.message ?? ''}`;
}
