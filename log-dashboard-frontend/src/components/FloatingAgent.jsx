import { useEffect, useMemo, useRef, useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { apiService, extractApiErrorMessage } from '../services/api';

// ── Ask mode: initial welcome message shown in the chat log
const INITIAL_ASK_MESSAGES = [
  {
    id: 'welcome-ask',
    role: 'assistant',
    text: 'Ask a question about your scoped logs. I can help with error patterns, service health, and recent activity.'
  }
];

export default function FloatingAgent() {
  const { user, role, isAdmin } = useAuth();
  const [isOpen, setIsOpen] = useState(false);

  // "ask" = Q&A chat, "summary" = structured summary output
  const [activeTab, setActiveTab] = useState('ask');

  // ── Ask mode state
  const [askMessages, setAskMessages] = useState(INITIAL_ASK_MESSAGES);
  const [askQuery, setAskQuery] = useState('');
  const [askLoading, setAskLoading] = useState(false);
  const [askError, setAskError] = useState('');

  // ── Summary mode state
  const [summaryQuery, setSummaryQuery] = useState('');
  const [summaryResult, setSummaryResult] = useState(null); // { text, timeContext, generatedAt }
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [summaryError, setSummaryError] = useState('');

  const messagesEndRef = useRef(null);
  const askInputRef = useRef(null);
  const summaryInputRef = useRef(null);

  const scopedServices = useMemo(() => {
    const assigned = Array.isArray(user?.assignedServices) ? user.assignedServices : [];
    const allowed = Array.isArray(user?.allowedServices) ? user.allowedServices : [];
    const source = assigned.length > 0 ? assigned : allowed;
    return source.map((item) => String(item || '').trim()).filter(Boolean);
  }, [user?.assignedServices, user?.allowedServices]);

  // Scroll chat to bottom when ask messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [askMessages, askLoading]);

  // Focus the right input when panel opens or tab switches
  useEffect(() => {
    if (!isOpen) return;
    const ref = activeTab === 'ask' ? askInputRef : summaryInputRef;
    window.setTimeout(() => ref.current?.focus(), 50);
  }, [isOpen, activeTab]);

  // Map the backend UserRole enum value to the agent's expected role string.
  // The Spring Boot UserRole enum has ADMIN and DEV.
  // The agent expects "admin" or "dev".
  const roleValue = isAdmin ? 'admin' : 'dev';

  // ── Ask mode submit
  const handleAskSubmit = async (event) => {
    event.preventDefault();
    const trimmed = askQuery.trim();
    if (!trimmed || askLoading) return;

    if (!isAdmin && !scopedServices.length) {
      setAskError('DEV requests require assigned services.');
      return;
    }

    setAskLoading(true);
    setAskError('');
    setAskMessages((prev) => [
      ...prev,
      { id: `user-${Date.now()}`, role: 'user', text: trimmed }
    ]);
    setAskQuery('');

    try {
      const response = await apiService.queryAgent({
        query: trimmed,
        mode: 'qa',
        role: roleValue,
        services: isAdmin ? [] : scopedServices
      });

      if (response && response.success === false) {
        const msg = String(response.message || 'Request failed');
        setAskError(msg);
        setAskMessages((prev) => [
          ...prev,
          { id: `err-${Date.now()}`, role: 'assistant', text: msg }
        ]);
        return;
      }

      const answerText = String(response?.answer || 'No data available for your role').trim();
      setAskMessages((prev) => [
        ...prev,
        { id: `ai-${Date.now()}`, role: 'assistant', text: answerText }
      ]);
    } catch (err) {
      const msg = extractApiErrorMessage(err, 'Agent request failed. Please try again.');
      setAskError(msg);
      setAskMessages((prev) => [
        ...prev,
        { id: `err-${Date.now()}`, role: 'assistant', text: msg }
      ]);
    } finally {
      setAskLoading(false);
    }
  };

  // ── Summary mode submit
  const handleSummarySubmit = async (event) => {
    event.preventDefault();
    const trimmed = summaryQuery.trim();
    if (!trimmed || summaryLoading) return;

    if (!isAdmin && !scopedServices.length) {
      setSummaryError('DEV requests require assigned services.');
      return;
    }

    setSummaryLoading(true);
    setSummaryError('');
    setSummaryResult(null);

    try {
      const response = await apiService.queryAgent({
        query: trimmed,
        mode: 'summary',
        role: roleValue,
        services: isAdmin ? [] : scopedServices
      });

      if (response && response.success === false) {
        setSummaryError(String(response.message || 'Summary generation failed'));
        return;
      }

      // PDF download path — primary success case
      if (response?.pdf_url) {
        setSummaryResult({
          pdfUrl: String(response.pdf_url),
          timeContext: trimmed,
          generatedAt: new Date().toLocaleString()
        });
        return;
      }

      // Fallback: plain text answer (shouldn't happen in summary mode, but handle gracefully)
      const text = String(response?.answer || '').trim();
      if (text) {
        setSummaryResult({
          text,
          timeContext: trimmed,
          generatedAt: new Date().toLocaleString()
        });
        return;
      }

      setSummaryError('No summary data available for your role and time range.');
    } catch (err) {
      setSummaryError(extractApiErrorMessage(err, 'Summary request failed. Please try again.'));
    } finally {
      setSummaryLoading(false);
    }
  };

  return (
    <>
      {/* Floating trigger button */}
      <button
        onClick={() => setIsOpen((o) => !o)}
        className="floating-agent-button"
        type="button"
        aria-label="Open AI Assistant"
        title="AI Log Assistant"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
      </button>

      <div className="floating-agent-tooltip">AI Log Assistant</div>

      {/* Panel */}
      {isOpen && (
        <div className="floating-agent-panel">
          {/* Header */}
          <div className="floating-agent-header">
            <div>
              <h3>AI Agent</h3>
              <p>Scoped to {String(role || 'user').toLowerCase()}</p>
            </div>
            <button
              onClick={() => setIsOpen(false)}
              className="floating-agent-close"
              type="button"
              aria-label="Close"
            >
              ✕
            </button>
          </div>

          {/* Tab switcher */}
          <div className="floating-agent-modes">
            <button
              type="button"
              className={`floating-agent-mode-btn ${activeTab === 'ask' ? 'active' : ''}`}
              onClick={() => setActiveTab('ask')}
              disabled={askLoading || summaryLoading}
            >
              Ask
            </button>
            <button
              type="button"
              className={`floating-agent-mode-btn ${activeTab === 'summary' ? 'active' : ''}`}
              onClick={() => setActiveTab('summary')}
              disabled={askLoading || summaryLoading}
            >
              Summary
            </button>
          </div>

          {/* ── ASK MODE ── */}
          {activeTab === 'ask' && (
            <>
              <div className="floating-agent-chat">
                {askMessages.map((msg) => (
                  <div key={msg.id} className={`floating-agent-msg ${msg.role}`}>
                    <div className="floating-agent-msg-bubble">{msg.text}</div>
                  </div>
                ))}
                {askLoading && (
                  <div className="floating-agent-msg assistant">
                    <div className="floating-agent-msg-bubble loading">
                      <span className="floating-agent-spinner" />
                      Analyzing...
                    </div>
                  </div>
                )}
                <div ref={messagesEndRef} />
              </div>

              <form className="floating-agent-form" onSubmit={handleAskSubmit}>
                <textarea
                  ref={askInputRef}
                  className="floating-agent-input"
                  value={askQuery}
                  onChange={(e) => setAskQuery(e.target.value)}
                  placeholder="Ask about logs, errors, services..."
                  rows={2}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      handleAskSubmit(e);
                    }
                  }}
                />
                <div className="floating-agent-actions">
                  {askError && <p className="floating-agent-error">{askError}</p>}
                  <button
                    type="submit"
                    className="floating-agent-send"
                    disabled={askLoading || !askQuery.trim()}
                  >
                    {askLoading ? '...' : '→'}
                  </button>
                </div>
              </form>
            </>
          )}

          {/* ── SUMMARY MODE ── */}
          {activeTab === 'summary' && (
            <div className="floating-agent-summary-shell">
              {/* Input form — always visible at top */}
              <form className="floating-agent-summary-form" onSubmit={handleSummarySubmit}>
                <textarea
                  ref={summaryInputRef}
                  className="floating-agent-input"
                  value={summaryQuery}
                  onChange={(e) => setSummaryQuery(e.target.value)}
                  placeholder="Describe the time range or scope, e.g. 'last 24 hours' or 'errors in auth-service this week'"
                  rows={2}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      handleSummarySubmit(e);
                    }
                  }}
                />
                <div className="floating-agent-actions">
                  {summaryError && <p className="floating-agent-error">{summaryError}</p>}
                  <button
                    type="submit"
                    className="floating-agent-send"
                    disabled={summaryLoading || !summaryQuery.trim()}
                  >
                    {summaryLoading ? '...' : 'Generate'}
                  </button>
                </div>
              </form>

              {/* Loading state */}
              {summaryLoading && (
                <div className="floating-agent-summary-loading">
                  <span className="floating-agent-spinner" style={{ borderTopColor: 'var(--accent-color)', borderColor: 'var(--border)' }} />
                  <span>Generating summary...</span>
                </div>
              )}

              {/* Result block */}
              {summaryResult && !summaryLoading && (
                <div className="floating-agent-summary-result">
                  <div className="floating-agent-summary-meta">
                    <span className="floating-agent-summary-label">Scope</span>
                    <span className="floating-agent-summary-value">{summaryResult.timeContext}</span>
                  </div>
                  <div className="floating-agent-summary-meta">
                    <span className="floating-agent-summary-label">Generated</span>
                    <span className="floating-agent-summary-value">{summaryResult.generatedAt}</span>
                  </div>

                  {/* PDF download — primary result */}
                  {summaryResult.pdfUrl && (
                    <div className="floating-agent-summary-download">
                      <p className="floating-agent-summary-download-hint">
                        Your report is ready. Click below to download.
                      </p>
                      <a
                        href={summaryResult.pdfUrl}
                        download
                        className="floating-agent-download-btn"
                        target="_blank"
                        rel="noreferrer"
                      >
                        ↓ Download PDF Report
                      </a>
                    </div>
                  )}

                  {/* Plain text fallback */}
                  {summaryResult.text && !summaryResult.pdfUrl && (
                    <div className="floating-agent-summary-body">
                      {summaryResult.text}
                    </div>
                  )}
                </div>
              )}

              {/* Empty state — no result yet and not loading */}
              {!summaryResult && !summaryLoading && !summaryError && (
                <div className="floating-agent-summary-empty">
                  Enter a time range or scope above to generate a structured log summary.
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </>
  );
}
