import React, { useEffect, useState, useCallback } from 'react';
import type { NotificationPreference, NotificationPreferenceUpdate } from '../types';
import { apiService } from '../services/api';

interface NotificationSettingsProps {
  onSaved?: () => void;
}

const NotificationSettings: React.FC<NotificationSettingsProps> = ({ onSaved }) => {
  const [pref, setPref]                 = useState<NotificationPreference | null>(null);
  const [loading, setLoading]           = useState(true);
  const [saving, setSaving]             = useState(false);
  const [error, setError]               = useState<string | null>(null);
  const [successMsg, setSuccessMsg]     = useState<string | null>(null);
  const [emailEnabled, setEmailEnabled] = useState(true);

  const loadPreferences = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiService.getNotificationPreferences();
      setPref(data);
      setEmailEnabled(data.emailEnabled);
    } catch {
      setError('Failed to load notification preferences. Please try again.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadPreferences(); }, [loadPreferences]);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    setSuccessMsg(null);
    try {
      const payload: NotificationPreferenceUpdate = { emailEnabled };
      const updated = await apiService.updateNotificationPreferences(payload);
      setPref(updated);
      setSuccessMsg('Preferences saved.');
      onSaved?.();
    } catch {
      setError('Failed to save. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  const isDirty = pref !== null && pref.emailEnabled !== emailEnabled;

  if (loading) {
    return (
      <div className="ns-loading">
        <span className="ns-spinner" />
        <span className="ns-loading-text">Loading…</span>
      </div>
    );
  }

  return (
    <div className="ns-body">
      {error && (
        <div className="ns-banner ns-banner-error" role="alert">
          <span>{error}</span>
          <button className="ns-banner-close" onClick={() => setError(null)} aria-label="Dismiss">✕</button>
        </div>
      )}
      {successMsg && (
        <div className="ns-banner ns-banner-success" role="status">
          <span>✓ {successMsg}</span>
          <button className="ns-banner-close" onClick={() => setSuccessMsg(null)} aria-label="Dismiss">✕</button>
        </div>
      )}

      <div className="ns-card">
        <div className="ns-row">
          <div className="ns-row-label">
            <span className="ns-row-title">Email Alerts</span>
            <span className="ns-row-desc">Receive email notifications for alerts on your services.</span>
          </div>
          <button
            role="switch"
            aria-checked={emailEnabled}
            onClick={() => setEmailEnabled(v => !v)}
            className={`ns-toggle${emailEnabled ? ' ns-toggle-on' : ''}`}
            aria-label="Toggle email alerts"
          >
            <span className="ns-toggle-thumb" />
          </button>
        </div>
      </div>

      <div className="ns-actions">
        <button
          onClick={handleSave}
          disabled={saving || !isDirty}
          className="ns-save-btn"
          aria-busy={saving}
        >
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
    </div>
  );
};

export default NotificationSettings;
