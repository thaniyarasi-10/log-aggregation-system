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

export default function FloatingAgent() {
  const { user, role, isAdmin } = useAuth();
  const [isOpen, setIsOpen] = useState(false);
  const [mode, setMode] = useState('qa');
  const [query, setQuery] = useState('');
  const [messages, setMessages] = useState(initialMessages);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [downloadUrl, setDownloadUrl] = useState('');
  const messagesEndRef = useRef(null);
  const inputRef = useRef(null);

  const scopedServices = useMemo(() => {
    const assigned = Array.isArray(user?.assignedServices) ? user.assignedServices : [];
    const allowed = Array.isArray(user?.allowedServices) ? user.allowedServices : [];
    const source = assigned.length > 0 ? assigned : allowed;
    return source.map((item) => String(item || '').trim()).filter(Boolean);
  }, [user?.assignedServices, user?.allowedServices]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, loading]);

  useEffect(() => {
    if (isOpen && inputRef.current) {
      inputRef.current.focus();
    }
  }, [isOpen]);

  const pushMessage = (entry) => {
    setMessages((current) => [...current, { id: `${Date.now()}-${current.length}`, ...entry }]);
  };

  const autoDownload = (url) => {
    const link = document.createElement('a');
    link.href = url;
    link.download = 'role-based-log-summary.pdf';
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

    const roleValue = String(user?.role || (isAdmin ? 'admin' : 'developer')).trim();
    if (!isAdmin && !scopedServices.length) {
      setError('Developer requests require assigned services.');
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

      console.log('[FloatingAgent] API Response:', { success: response?.success, mode, hasAnswer: !!response?.answer, hasPdfUrl: !!response?.pdf_url, hasMessage: !!response?.message });
      
      // Safety check: response should have success flag
      if (response && typeof response.success === 'boolean') {
        if (!response.success) {
          // Error response
          const errorMessage = String(response?.message || 'Request failed with unknown error');
          console.warn('[FloatingAgent] Error from backend:', errorMessage);
          setError(errorMessage);
          pushMessage({ role: 'assistant', text: errorMessage });
          setQuery('');
          return;
        }
      } else {
        // Old format or unexpected response structure - log warning but continue
        console.warn('[FloatingAgent] Unexpected response format:', response);
      }
      
      if (mode === 'summary') {
        const pdfUrl = String(response?.pdf_url || '').trim();
        console.log('[FloatingAgent] Summary mode - pdfUrl:', pdfUrl ? `${pdfUrl.length} chars` : 'empty');
        
        if (pdfUrl) {
          setDownloadUrl(pdfUrl);
          pushMessage({ role: 'assistant', text: 'Summary generated. The PDF download has started.' });
          autoDownload(pdfUrl);
        } else {
          const answerText = String(response?.answer || 'No data available for your role').trim();
          pushMessage({ role: 'assistant', text: answerText });
        }
      } else {
        const answerText = String(response?.answer || 'No data available for your role').trim();
        console.log('[FloatingAgent] QA mode - answer length:', answerText.length);
        pushMessage({ role: 'assistant', text: answerText });
      }

      setQuery('');
    } catch (caughtError) {
      const message = extractApiErrorMessage(caughtError, 'Agent request failed');
      console.error('[FloatingAgent] Caught error:', { message, errorType: caughtError?.response?.status });
      setError(message);
      pushMessage({ role: 'assistant', text: message });
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      {/* Floating Button */}
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="floating-agent-button"
        type="button"
        aria-label="Open AI Assistant"
        title="How can I help you today?"
      >
        <svg
          width="24"
          height="24"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
      </button>

      {/* Tooltip */}
      <div className="floating-agent-tooltip">How can I help you today?</div>

      {/* Chat Window */}
      {isOpen && (
        <div className="floating-agent-panel">
          {/* Header */}
          <div className="floating-agent-header">
            <div>
              <h3>AI Agent</h3>
              <p>Scoped log analysis for {String(role || 'user').toLowerCase()}</p>
            </div>
            <button
              onClick={() => setIsOpen(false)}
              className="floating-agent-close"
              type="button"
              aria-label="Close AI Assistant"
            >
              ✕
            </button>
          </div>

          {/* Mode Toggle */}
          <div className="floating-agent-modes">
            <button
              type="button"
              className={`floating-agent-mode-btn ${mode === 'qa' ? 'active' : ''}`}
              onClick={() => setMode('qa')}
              disabled={loading}
            >
              Ask
            </button>
            <button
              type="button"
              className={`floating-agent-mode-btn ${mode === 'summary' ? 'active' : ''}`}
              onClick={() => setMode('summary')}
              disabled={loading}
            >
              Summary
            </button>
          </div>

          {/* Messages */}
          <div className="floating-agent-chat">
            {messages.map((message) => (
              <div key={message.id} className={`floating-agent-msg ${message.role}`}>
                <div className="floating-agent-msg-bubble">{message.text}</div>
              </div>
            ))}
            {loading && (
              <div className="floating-agent-msg assistant">
                <div className="floating-agent-msg-bubble loading">
                  <span className="floating-agent-spinner" />
                  Analyzing...
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>

          {/* Input */}
          <form className="floating-agent-form" onSubmit={handleSubmit}>
            <textarea
              ref={inputRef}
              className="floating-agent-input"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={mode === 'qa' ? 'Ask about logs...' : 'Describe timeframe...'}
              rows="2"
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleSubmit(e);
                }
              }}
            />
            <div className="floating-agent-actions">
              {error && <p className="floating-agent-error">{error}</p>}
              <button
                type="submit"
                className="floating-agent-send"
                disabled={loading || !query.trim()}
              >
                {loading ? '...' : '→'}
              </button>
            </div>
          </form>

          {/* Download Link */}
          {downloadUrl && (
            <div className="floating-agent-footer">
              <a href={downloadUrl} target="_blank" rel="noreferrer" className="floating-agent-download">
                Download PDF
              </a>
            </div>
          )}
        </div>
      )}
    </>
  );
}
