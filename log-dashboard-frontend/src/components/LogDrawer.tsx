import { useEffect, useRef, useState, useCallback } from 'react';
import type { LogEvent } from '../types';

// ─── helpers ────────────────────────────────────────────────────────────────

function formatTimestamp(raw?: string): string {
  if (!raw) return '—';
  try {
    return new Date(raw).toLocaleString('en-IN', {
      timeZone: 'Asia/Kolkata',
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      fractionalSecondDigits: 3,
    } as Intl.DateTimeFormatOptions);
  } catch {
    return raw;
  }
}

function levelClass(level: string): string {
  const n = level.toUpperCase();
  if (n === 'ERROR') return 'tag-error';
  if (n === 'WARN') return 'tag-warn';
  if (n === 'INFO') return 'tag-info';
  return 'tag-debug';
}

function statusClass(code?: number): string {
  if (!code) return '';
  if (code >= 500) return 'ld-status-5xx';
  if (code >= 400) return 'ld-status-4xx';
  if (code >= 300) return 'ld-status-3xx';
  return 'ld-status-2xx';
}

function useCopy(timeout = 1500) {
  const [copied, setCopied] = useState<string | null>(null);
  const copy = useCallback((text: string, key: string) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(key);
      setTimeout(() => setCopied(null), timeout);
    });
  }, [timeout]);
  return { copied, copy };
}

// ─── JSON tree ───────────────────────────────────────────────────────────────

type JsonValue = string | number | boolean | null | JsonValue[] | { [k: string]: JsonValue };

function JsonNode({ value, depth = 0 }: { value: JsonValue; depth?: number }) {
  const [open, setOpen] = useState(depth < 2);

  if (value === null) return <span className="ld-json-null">null</span>;
  if (typeof value === 'boolean') return <span className="ld-json-bool">{String(value)}</span>;
  if (typeof value === 'number') return <span className="ld-json-num">{value}</span>;
  if (typeof value === 'string') return <span className="ld-json-str">"{value}"</span>;

  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="ld-json-punct">[]</span>;
    return (
      <span>
        <button className="ld-json-toggle" onClick={() => setOpen(o => !o)} aria-label={open ? 'Collapse' : 'Expand'}>
          {open ? '▾' : '▸'}
        </button>
        <span className="ld-json-punct">[</span>
        {open ? (
          <span className="ld-json-block">
            {value.map((item, i) => (
              <span key={i} className="ld-json-line">
                <JsonNode value={item as JsonValue} depth={depth + 1} />
                {i < value.length - 1 && <span className="ld-json-punct">,</span>}
              </span>
            ))}
          </span>
        ) : (
          <span className="ld-json-ellipsis"> {value.length} items </span>
        )}
        <span className="ld-json-punct">]</span>
      </span>
    );
  }

  // object
  const entries = Object.entries(value as Record<string, JsonValue>);
  if (entries.length === 0) return <span className="ld-json-punct">{'{}'}</span>;
  return (
    <span>
      <button className="ld-json-toggle" onClick={() => setOpen(o => !o)} aria-label={open ? 'Collapse' : 'Expand'}>
        {open ? '▾' : '▸'}
      </button>
      <span className="ld-json-punct">{'{'}</span>
      {open ? (
        <span className="ld-json-block">
          {entries.map(([k, v], i) => (
            <span key={k} className="ld-json-line">
              <span className="ld-json-key">"{k}"</span>
              <span className="ld-json-punct">: </span>
              <JsonNode value={v} depth={depth + 1} />
              {i < entries.length - 1 && <span className="ld-json-punct">,</span>}
            </span>
          ))}
        </span>
      ) : (
        <span className="ld-json-ellipsis"> {entries.length} fields </span>
      )}
      <span className="ld-json-punct">{'}'}</span>
    </span>
  );
}

// ─── field row ───────────────────────────────────────────────────────────────

function Field({
  label,
  value,
  mono = false,
  copyKey,
  copied,
  onCopy,
}: {
  label: string;
  value: string | number | undefined | null;
  mono?: boolean;
  copyKey?: string;
  copied: string | null;
  onCopy: (text: string, key: string) => void;
}) {
  if (value === undefined || value === null || value === '') return null;
  const display = String(value);
  const key = copyKey ?? label;
  return (
    <div className="ld-field">
      <span className="ld-field-label">{label}</span>
      <span className={`ld-field-value${mono ? ' ld-mono' : ''}`}>{display}</span>
      <button
        className={`ld-copy-btn${copied === key ? ' ld-copy-done' : ''}`}
        onClick={() => onCopy(display, key)}
        title="Copy"
        aria-label={`Copy ${label}`}
      >
        {copied === key ? (
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M3 8l4 4 6-7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        ) : (
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <rect x="5" y="5" width="8" height="8" rx="1" stroke="currentColor" strokeWidth="1.5" />
            <path d="M3 11V3h8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
        )}
      </button>
    </div>
  );
}

// ─── tabs ────────────────────────────────────────────────────────────────────

type Tab = 'overview' | 'json' | 'metadata' | 'raw';

// ─── main component ──────────────────────────────────────────────────────────

type Props = {
  log: LogEvent | null;
  onClose: () => void;
};

export default function LogDrawer({ log, onClose }: Props) {
  const [tab, setTab] = useState<Tab>('overview');
  const [jsonCopied, setJsonCopied] = useState(false);
  const drawerRef = useRef<HTMLDivElement>(null);
  const { copied, copy } = useCopy();

  // ESC to close
  useEffect(() => {
    if (!log) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [log, onClose]);

  // Reset tab when a new log is opened
  useEffect(() => {
    if (log) setTab('overview');
  }, [log]);

  // Trap focus inside drawer when open
  useEffect(() => {
    if (log && drawerRef.current) {
      drawerRef.current.focus();
    }
  }, [log]);

  if (!log) return null;

  const ts = log['@timestamp'];
  const jsonPayload = JSON.stringify(
    Object.fromEntries(
      Object.entries(log).filter(([, v]) => v !== undefined && v !== null && v !== '')
    ),
    null,
    2
  );

  const copyJson = () => {
    navigator.clipboard.writeText(jsonPayload).then(() => {
      setJsonCopied(true);
      setTimeout(() => setJsonCopied(false), 1500);
    });
  };

  // Build metadata fields: everything that isn't the primary display fields
  const primaryKeys = new Set(['@timestamp', 'level', 'service', 'message', 'environment', 'instance']);
  const metaEntries = Object.entries(log).filter(
    ([k, v]) => !primaryKeys.has(k) && v !== undefined && v !== null && v !== ''
  );

  return (
    <>
      {/* Backdrop — click outside to close */}
      <div
        className="ld-backdrop"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Drawer panel */}
      <div
        ref={drawerRef}
        className="ld-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Log details"
        tabIndex={-1}
      >
        {/* ── Header ── */}
        <div className="ld-header">
          <div className="ld-header-meta">
            <span className={`tag ${levelClass(String(log.level))}`}>{log.level}</span>
            <span className="ld-header-service">{log.service}</span>
            {log.statusCode != null && (
              <span className={`ld-status-badge ${statusClass(log.statusCode)}`}>
                {log.statusCode}
              </span>
            )}
            {log.endpoint && (
              <span className="ld-header-endpoint ld-mono">{log.method ? `${log.method} ` : ''}{log.endpoint}</span>
            )}
          </div>
          <div className="ld-header-right">
            <span className="ld-header-ts ld-mono">{formatTimestamp(ts)}</span>
            <button className="ld-close-btn" onClick={onClose} aria-label="Close log details">
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M3 3l10 10M13 3L3 13" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
              </svg>
            </button>
          </div>
        </div>

        {/* Trace ID strip — shown only when present */}
        {log.traceId && (
          <div className="ld-trace-strip">
            <span className="ld-trace-label">Trace</span>
            <span className="ld-trace-id ld-mono">{log.traceId}</span>
            <button
              className={`ld-copy-btn${copied === 'traceId' ? ' ld-copy-done' : ''}`}
              onClick={() => copy(log.traceId!, 'traceId')}
              title="Copy trace ID"
              aria-label="Copy trace ID"
            >
              {copied === 'traceId' ? (
                <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path d="M3 8l4 4 6-7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              ) : (
                <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <rect x="5" y="5" width="8" height="8" rx="1" stroke="currentColor" strokeWidth="1.5" />
                  <path d="M3 11V3h8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                </svg>
              )}
            </button>
          </div>
        )}

        {/* ── Tabs ── */}
        <div className="ld-tabs" role="tablist">
          {(['overview', 'json', 'metadata', 'raw'] as Tab[]).map(t => (
            <button
              key={t}
              role="tab"
              aria-selected={tab === t}
              className={`ld-tab${tab === t ? ' ld-tab-active' : ''}`}
              onClick={() => setTab(t)}
            >
              {t.charAt(0).toUpperCase() + t.slice(1)}
            </button>
          ))}
        </div>

        {/* ── Tab content ── */}
        <div className="ld-body">

          {/* OVERVIEW */}
          {tab === 'overview' && (
            <div className="ld-overview">
              {/* Full message */}
              <section className="ld-section">
                <h4 className="ld-section-title">Message</h4>
                <div className="ld-message-block ld-mono">{log.message}</div>
              </section>

              {/* Identity */}
              <section className="ld-section">
                <h4 className="ld-section-title">Identity</h4>
                <div className="ld-fields">
                  <Field label="Service"     value={log.service}     copied={copied} onCopy={copy} />
                  <Field label="Environment" value={log.environment} copied={copied} onCopy={copy} />
                  <Field label="Instance"    value={log.instance}    copied={copied} onCopy={copy} />
                  <Field label="Timestamp"   value={formatTimestamp(ts)} mono copied={copied} onCopy={copy} copyKey="timestamp" />
                </div>
              </section>

              {/* Request */}
              {(log.method || log.endpoint || log.statusCode != null || log.responseTime != null) && (
                <section className="ld-section">
                  <h4 className="ld-section-title">Request</h4>
                  <div className="ld-fields">
                    <Field label="Method"        value={log.method}                                  copied={copied} onCopy={copy} />
                    <Field label="Endpoint"      value={log.endpoint}      mono                      copied={copied} onCopy={copy} />
                    <Field label="Status"        value={log.statusCode}                              copied={copied} onCopy={copy} />
                    <Field label="Response time" value={log.responseTime != null ? `${Math.round(log.responseTime)}ms` : undefined} mono copied={copied} onCopy={copy} copyKey="responseTime" />
                  </div>
                </section>
              )}

              {/* Tracing */}
              {(log.traceId || log.spanId) && (
                <section className="ld-section">
                  <h4 className="ld-section-title">Tracing</h4>
                  <div className="ld-fields">
                    <Field label="Trace ID" value={log.traceId} mono copied={copied} onCopy={copy} copyKey="traceId" />
                    <Field label="Span ID"  value={log.spanId}  mono copied={copied} onCopy={copy} copyKey="spanId" />
                    <Field label="User ID"  value={log.userId}  mono copied={copied} onCopy={copy} copyKey="userId" />
                  </div>
                </section>
              )}

              {/* Error details */}
              {(log.errorCode || log.errorDetails) && (
                <section className="ld-section">
                  <h4 className="ld-section-title">Error</h4>
                  <div className="ld-fields">
                    <Field label="Error code" value={log.errorCode} mono copied={copied} onCopy={copy} copyKey="errorCode" />
                  </div>
                  {log.errorDetails && (
                    <pre className="ld-stack-trace">{log.errorDetails}</pre>
                  )}
                </section>
              )}
            </div>
          )}

          {/* JSON */}
          {tab === 'json' && (
            <div className="ld-json-tab">
              <div className="ld-json-toolbar">
                <span className="ld-json-toolbar-label">Structured log</span>
                <button
                  className={`ld-copy-json-btn${jsonCopied ? ' ld-copy-done' : ''}`}
                  onClick={copyJson}
                >
                  {jsonCopied ? (
                    <>
                      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M3 8l4 4 6-7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                      Copied
                    </>
                  ) : (
                    <>
                      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <rect x="5" y="5" width="8" height="8" rx="1" stroke="currentColor" strokeWidth="1.5" />
                        <path d="M3 11V3h8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                      </svg>
                      Copy JSON
                    </>
                  )}
                </button>
              </div>
              <div className="ld-json-tree ld-mono">
                <JsonNode value={JSON.parse(jsonPayload) as JsonValue} depth={0} />
              </div>
            </div>
          )}

          {/* METADATA */}
          {tab === 'metadata' && (
            <div className="ld-metadata-tab">
              {metaEntries.length === 0 ? (
                <p className="ld-empty">No additional metadata fields.</p>
              ) : (
                <div className="ld-fields ld-fields-wide">
                  {metaEntries.map(([k, v]) => (
                    <Field
                      key={k}
                      label={k}
                      value={typeof v === 'object' ? JSON.stringify(v) : String(v)}
                      mono
                      copyKey={k}
                      copied={copied}
                      onCopy={copy}
                    />
                  ))}
                </div>
              )}
            </div>
          )}

          {/* RAW */}
          {tab === 'raw' && (
            <div className="ld-raw-tab">
              <div className="ld-json-toolbar">
                <span className="ld-json-toolbar-label">Raw JSON</span>
                <button
                  className={`ld-copy-json-btn${jsonCopied ? ' ld-copy-done' : ''}`}
                  onClick={copyJson}
                >
                  {jsonCopied ? (
                    <>
                      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <path d="M3 8l4 4 6-7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                      Copied
                    </>
                  ) : (
                    <>
                      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                        <rect x="5" y="5" width="8" height="8" rx="1" stroke="currentColor" strokeWidth="1.5" />
                        <path d="M3 11V3h8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                      </svg>
                      Copy
                    </>
                  )}
                </button>
              </div>
              <pre className="ld-raw-pre ld-mono">{jsonPayload}</pre>
            </div>
          )}

        </div>
      </div>
    </>
  );
}
