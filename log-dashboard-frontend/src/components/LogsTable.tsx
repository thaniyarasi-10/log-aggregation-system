import type { LogEvent } from '../types';

type Props = {
  logs: LogEvent[];
  loading: boolean;
  error: string;
  searchTerm?: string;
};

export default function LogsTable({ logs, loading, error, searchTerm }: Props) {
  const highlightText = (text: string, term?: string) => {
    if (!term || !term.trim()) {
      return text;
    }

    const normalized = term.toLowerCase();
    const parts: (string | JSX.Element)[] = [];
    let lastIndex = 0;

    const lowerText = text.toLowerCase();
    let index = lowerText.indexOf(normalized);

    while (index !== -1) {
      if (index > lastIndex) {
        parts.push(text.substring(lastIndex, index));
      }
      parts.push(
        <mark key={`${index}-${term}`} className="log-search-hit">
          {text.substring(index, index + term.length)}
        </mark>
      );
      lastIndex = index + term.length;
      index = lowerText.indexOf(normalized, lastIndex);
    }

    if (lastIndex < text.length) {
      parts.push(text.substring(lastIndex));
    }

    return parts.length > 0 ? parts : text;
  };
  if (loading) {
    return (
      <section className="glass-panel table-container">
        <div className="table-header"><h3>Live Logs</h3></div>
        <div className="table-scroll-area" style={{ padding: '1rem' }}>Loading logs...</div>
      </section>
    );
  }

  if (error) {
    return (
      <section className="glass-panel table-container">
        <div className="table-header"><h3>Live Logs</h3></div>
        <div className="table-scroll-area" style={{ padding: '1rem' }}>
          <p className="error">{error}</p>
        </div>
      </section>
    );
  }

  const levelClass = (level: string) => {
    const normalized = level.toUpperCase();
    if (normalized === 'ERROR') return 'tag-error';
    if (normalized === 'WARN') return 'tag-warn';
    if (normalized === 'INFO') return 'tag-info';
    return 'tag-debug';
  };

  return (
    <section className="glass-panel table-container">
      <div className="table-header"><h3>Live Logs</h3></div>
      <div className="table-scroll-area">
        <table className="log-table">
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Service</th>
              <th>Level</th>
              <th>Message</th>
              <th>Status</th>
              <th>Response</th>
            </tr>
          </thead>
          <tbody>
            {logs.map((log, index) => (
              <tr
                key={log.id || `${log.timestamp}-${index}`}
                className={Number(log.responseTime || 0) > 1000 ? 'slow-log' : ''}
              >
                <td>{new Date(log.timestamp).toLocaleString()}</td>
                <td>{log.service}</td>
                <td><span className={`tag ${levelClass(String(log.level))}`}>{log.level}</span></td>
                <td><div className="message-cell">{highlightText(log.message, searchTerm)}</div></td>
                <td>{log.statusCode ?? '-'}</td>
                <td>{log.responseTime ? `${Math.round(log.responseTime)}ms` : '-'}</td>
              </tr>
            ))}
            {!logs.length && (
              <tr>
                <td colSpan={6}>No logs available.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
