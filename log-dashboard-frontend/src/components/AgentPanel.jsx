import { useEffect, useMemo, useRef, useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { apiService, extractApiErrorMessage } from '../services/api';

const initialMessages = [
  {
    id: 'welcome',
    role: 'assistant',
    text: 'Ask a question about scoped logs or generate a role-based summary.'
  }
];

function safeJoin(list) {
  return Array.isArray(list)
    ? list.map((item) => String(item || '').trim()).filter(Boolean).join(', ')
    : '';
}

export default function AgentPanel() {
  const { user, role, isAdmin } = useAuth();
  const [mode, setMode] = useState('qa');
  const [query, setQuery] = useState('');
  const [messages, setMessages] = useState(initialMessages);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [downloadUrl, setDownloadUrl] = useState('');
  const messagesEndRef = useRef(null);

  const scopedServices = useMemo(() => {
    const assigned = Array.isArray(user?.assignedServices) ? user.assignedServices : [];
    const allowed = Array.isArray(user?.allowedServices) ? user.allowedServices : [];
    const source = assigned.length > 0 ? assigned : allowed;
    return source.map((item) => String(item || '').trim()).filter(Boolean);
  }, [user?.assignedServices, user?.allowedServices]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, loading]);

  const pushMessage = (entry) => {
    setMessages((current) => [...current, { id: `${Date.now()}-${current.length}`, ...entry }]);
  };

  const autoDownload = (url) => {
    const link = document.createElement('a');
    link.href = url;
    link.download = 'log-summary.pdf';
    link.rel = 'noreferrer';
    document.body.appendChild(link);
    link.click();
    link.remove();
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    const trimmedQuery = query.trim();
    if (!trimmedQuery || loading) {
      return;
    }

    const roleValue = String(user?.role || (isAdmin ? 'admin' : 'dev')).trim();
    if (!isAdmin && !scopedServices.length) {
      setError('DEV requests require assigned services.');
      return;
    }

    setLoading(true);
    setError('');
    pushMessage({ role: 'user', text: trimmedQuery });

    try {
      const response = await apiService.queryAgent({
        query: trimmedQuery,
        mode,
        role: roleValue,
        services: isAdmin ? [] : scopedServices
      });

      if (mode === 'summary') {
        const pdfUrl = String(response?.pdf_url || '');
        if (pdfUrl) {
          setDownloadUrl(pdfUrl);
          pushMessage({ role: 'assistant', text: 'Summary generated. The PDF download has started.' });
          autoDownload(pdfUrl);
        } else {
          pushMessage({ role: 'assistant', text: response?.answer || 'No data available for your role' });
        }
      } else {
        pushMessage({ role: 'assistant', text: response?.answer || 'No data available for your role' });
      }

      setQuery('');
    } catch (caughtError) {
      const message = extractApiErrorMessage(caughtError, 'Agent request failed');
      setError(message);
      pushMessage({ role: 'assistant', text: message });
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="glass-panel agent-panel">
      <div className="agent-panel-header">
        <div>
          <p className="agent-eyebrow">AI Agent Panel</p>
          <h2>Scoped log analysis for {String(role || 'authenticated user').toLowerCase()}</h2>
          <p className="agent-subtitle">
            {isAdmin
              ? 'Admins can query every service in the cluster.'
              : `DEV scope: ${safeJoin(scopedServices) || 'No assigned services'}`}
          </p>
        </div>

        <div className="agent-mode-switch" role="tablist" aria-label="Agent mode">
          <button
            type="button"
            className={`agent-mode-button ${mode === 'qa' ? 'active' : ''}`}
            onClick={() => setMode('qa')}
            disabled={loading}
          >
            Ask Question
          </button>
          <button
            type="button"
            className={`agent-mode-button ${mode === 'summary' ? 'active' : ''}`}
            onClick={() => setMode('summary')}
            disabled={loading}
          >
            Generate Summary
          </button>
        </div>
      </div>

      <div className="agent-chat-shell">
        <div className="agent-chat-log" aria-live="polite">
          {messages.map((message) => (
            <div key={message.id} className={`agent-message ${message.role === 'user' ? 'user' : 'assistant'}`}>
              <div className="agent-message-role">{message.role === 'user' ? 'You' : 'Agent'}</div>
              <div className="agent-message-text">{message.text}</div>
            </div>
          ))}
          {loading && (
            <div className="agent-message assistant">
              <div className="agent-message-role">Agent</div>
              <div className="agent-message-text agent-loading">
                <span className="agent-spinner" aria-hidden="true" />
                Analyzing scoped logs...
              </div>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>

        <form className="agent-composer" onSubmit={handleSubmit}>
          <textarea
            className="form-control agent-input"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={mode === 'qa' ? 'Ask about errors, services, timestamps, or root cause...' : 'Describe the timeframe or concern for the summary...'}
            rows={3}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                void handleSubmit(event);
              }
            }}
          />
          <div className="agent-composer-footer">
            <div className="agent-status-row">
              <span className={`agent-status-pill ${loading ? 'busy' : 'ready'}`}>
                {loading ? 'Working' : 'Ready'}
              </span>
              {downloadUrl ? (
                <a className="agent-download-link" href={downloadUrl} target="_blank" rel="noreferrer">
                  Download latest PDF
                </a>
              ) : null}
            </div>
            <button type="submit" className="btn agent-submit" disabled={loading || !query.trim()}>
              {loading ? 'Processing...' : mode === 'qa' ? 'Send Question' : 'Create PDF Summary'}
            </button>
          </div>
          {error ? <p className="agent-error">{error}</p> : null}
        </form>
      </div>
    </section>
  );
}
