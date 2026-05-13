import { useEffect, useRef, useState } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';
import { apiService } from '../services/api';
import type { AlertItem } from '../types';
import NotificationSettings from './NotificationSettings';

const getClassName = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'header-nav-link active' : 'header-nav-link';

// ── Moon icon (light mode → click to go dark)
function MoonIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
    </svg>
  );
}

// ── Sun icon (dark mode → click to go light)
function SunIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="5" />
      <line x1="12" y1="1" x2="12" y2="3" />
      <line x1="12" y1="21" x2="12" y2="23" />
      <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
      <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
      <line x1="1" y1="12" x2="3" y2="12" />
      <line x1="21" y1="12" x2="23" y2="12" />
      <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
      <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
    </svg>
  );
}

// ── Bell icon SVG
function BellIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
      <path d="M13.73 21a2 2 0 0 1-3.46 0" />
    </svg>
  );
}

// ── Person icon SVG
function PersonIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
      <circle cx="12" cy="7" r="4" />
    </svg>
  );
}

export default function Navbar() {
  const { user, role, canAccessUsers, canAccessServices, logout } = useAuth();
  const { theme, toggleTheme } = useTheme();

  const [profileOpen, setProfileOpen]   = useState(false);
  const [notifOpen, setNotifOpen]       = useState(false);
  const [notifPanelOpen, setNotifPanelOpen] = useState(false);
  const [alerts, setAlerts]             = useState<AlertItem[]>([]);
  const [alertsLoading, setAlertsLoading] = useState(false);

  const profileRef = useRef<HTMLDivElement>(null);
  const notifRef = useRef<HTMLDivElement>(null);

  // Close dropdowns when clicking outside
  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (profileRef.current && !profileRef.current.contains(e.target as Node)) {
        setProfileOpen(false);
      }
      if (notifRef.current && !notifRef.current.contains(e.target as Node)) {
        setNotifOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Background polling — runs every 60 s regardless of panel state.
  // This keeps the red-dot indicator accurate without requiring the user to open the panel.
  useEffect(() => {
    let active = true;

    const poll = async () => {
      try {
        const data = await apiService.fetchAlerts();
        if (active) setAlerts(data);
      } catch {
        // silently ignore — stale data is fine for the indicator
      }
    };

    void poll(); // immediate fetch on mount
    const timer = window.setInterval(() => { void poll(); }, 60_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  // Refresh immediately when the panel is opened so the user always sees fresh data.
  // Don't show the loading spinner if we already have alerts — just update silently.
  useEffect(() => {
    if (!notifOpen) return;
    let active = true;
    // Only show loading spinner on first open (no existing data)
    if (alerts.length === 0) setAlertsLoading(true);
    apiService.fetchAlerts()
      .then((data) => { if (active) setAlerts(data); })
      .catch(() => { /* keep existing alerts on error */ })
      .finally(() => { if (active) setAlertsLoading(false); });
    return () => { active = false; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [notifOpen]);

  const displayName = user?.name || user?.email || 'User';
  const displayRole = role || 'User';

  return (
    <>
      <header className="glass-panel dashboard-header">
        <div className="header-logo">
          <span className="logo-icon">◷</span>
          <h1 className="header-title">LogFlow Observability</h1>
        </div>

        <div className="header-actions">
          <nav className="header-nav">
            <NavLink to="/logs" className={getClassName}>Logs</NavLink>
            {canAccessUsers && <NavLink to="/users" className={getClassName}>Users</NavLink>}
            {canAccessServices && <NavLink to="/services" className={getClassName}>Services</NavLink>}
          </nav>

          {/* Theme toggle — SVG moon/sun */}
          <button
            className="btn header-icon-btn"
            onClick={toggleTheme}
            aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            title={theme === 'dark' ? 'Light mode' : 'Dark mode'}
          >
            {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
          </button>

          {/* Notification bell — red dot indicator, no count number */}
          <div className="header-dropdown-wrap" ref={notifRef}>
            <button
              className={`btn header-icon-btn ${notifOpen ? 'active' : ''}`}
              onClick={() => { setNotifOpen((o) => !o); setProfileOpen(false); }}
              aria-label="Notifications"
              title="Alerts"
            >
              <BellIcon />
              {alerts.length > 0 && !notifOpen && (
                <span className="header-notif-dot" aria-hidden="true" />
              )}
            </button>

            {notifOpen && (
              <div className="header-dropdown header-notif-panel">
                <div className="header-dropdown-title">Alerts</div>
                {alertsLoading && (
                  <div className="header-dropdown-empty">Loading...</div>
                )}
                {!alertsLoading && alerts.length === 0 && (
                  <div className="header-dropdown-empty">No alerts</div>
                )}
                {!alertsLoading && alerts.length > 0 && (
                  <div className="header-notif-list">
                    {alerts.map((alert, i) => (
                      <div
                        key={`${alert.service}-${alert.timestamp ?? i}`}
                        className={`header-notif-row ${alert.severity === 'CRITICAL' ? 'notif-critical' : 'notif-warning'}`}
                      >
                        <div className="header-notif-top">
                          <span className="header-notif-service">{alert.service}</span>
                          <span className={`tag ${alert.severity === 'CRITICAL' ? 'tag-error' : 'tag-warn'}`}>
                            {alert.severity}
                          </span>
                        </div>
                        <div className="header-notif-message">{alert.message}</div>
                        {alert.timestamp && (
                          <div className="header-notif-time">
                            {new Date(alert.timestamp).toLocaleString()}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </header>

      {/* Profile button — fixed bottom-left, outside the header flow */}
      <div className="profile-anchor" ref={profileRef}>
        <button
          className={`profile-trigger ${profileOpen ? 'active' : ''}`}
          onClick={() => { setProfileOpen((o) => !o); setNotifOpen(false); setNotifPanelOpen(false); }}
          aria-label="Profile"
          title="Profile"
        >
          <PersonIcon />
        </button>

        {profileOpen && (
          <div className="profile-dropdown">
            <div className="header-profile-info">
              <div className="header-profile-name">{displayName}</div>
              {user?.email && user.email !== displayName && (
                <div className="header-profile-email">{user.email}</div>
              )}
              <div className="header-profile-role">{displayRole}</div>
            </div>
            <div className="header-dropdown-divider" />
            <button
              className="header-profile-action"
              onClick={() => { setProfileOpen(false); setNotifPanelOpen(true); }}
            >
              🔔 Notification Settings
            </button>
            <div className="header-dropdown-divider" />
            <button
              className="header-profile-logout"
              onClick={() => { setProfileOpen(false); void logout(); }}
            >
              Sign out
            </button>
          </div>
        )}

        {/* Notification settings side panel — slides in above the profile anchor */}
        {notifPanelOpen && (
          <>
            <div
              className="ns-panel-backdrop"
              onClick={() => setNotifPanelOpen(false)}
              aria-hidden="true"
            />
            <div className="ns-panel" role="dialog" aria-label="Notification Settings">
              <div className="ns-panel-header">
                <span className="ns-panel-title">Notification Settings</span>
                <button
                  className="ns-panel-close"
                  onClick={() => setNotifPanelOpen(false)}
                  aria-label="Close"
                >
                  <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="M3 3l10 10M13 3L3 13" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                  </svg>
                </button>
              </div>
              <NotificationSettings onSaved={() => setNotifPanelOpen(false)} />
            </div>
          </>
        )}
      </div>
    </>
  );
}
