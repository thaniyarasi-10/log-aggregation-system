
CREATE TABLE IF NOT EXISTS notification_preference (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  VARCHAR(50) NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    email_enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    min_severity             VARCHAR(20) NOT NULL DEFAULT 'WARNING',
    cooldown_override_minutes INTEGER,
    created_at               TIMESTAMP,
    updated_at               TIMESTAMP,
    CONSTRAINT uq_notif_pref_user UNIQUE (user_id)
);

COMMENT ON TABLE  notification_preference                        IS 'Per-user email notification preferences';
COMMENT ON COLUMN notification_preference.email_enabled          IS 'Master switch — false suppresses all emails for this user';
COMMENT ON COLUMN notification_preference.min_severity           IS 'Minimum severity to notify on: INFO < WARNING < ERROR < CRITICAL';
COMMENT ON COLUMN notification_preference.cooldown_override_minutes IS 'Per-user cooldown override; NULL = use global default';

-- alert_notification_log
-- Audit trail for every email notification attempt (SENT / FAILED / SKIPPED).
-- Also used for deduplication: the most-recent SENT row for (recipient, alert_hash)
-- is checked before dispatching a new email.
CREATE TABLE IF NOT EXISTS alert_notification_log (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_hash       VARCHAR(64) NOT NULL,
    alert_id         VARCHAR(200) NOT NULL,
    service_name     VARCHAR(200) NOT NULL,
    severity         VARCHAR(20) NOT NULL,
    recipient_email  VARCHAR(320) NOT NULL,
    delivery_status  VARCHAR(20) NOT NULL,   -- SENT | FAILED | SKIPPED
    sent_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    retry_count      INTEGER     NOT NULL DEFAULT 0,
    failure_reason   VARCHAR(1000)
);

COMMENT ON TABLE  alert_notification_log                IS 'Audit trail for alert email notification attempts';
COMMENT ON COLUMN alert_notification_log.alert_hash     IS 'SHA-256 of (service|severity) — deduplication key';
COMMENT ON COLUMN alert_notification_log.delivery_status IS 'SENT, FAILED, or SKIPPED';

-- Indexes for deduplication query and time-based cleanup
CREATE INDEX IF NOT EXISTS idx_anl_recipient_hash
    ON alert_notification_log (recipient_email, alert_hash);

CREATE INDEX IF NOT EXISTS idx_anl_sent_at
    ON alert_notification_log (sent_at);

CREATE INDEX IF NOT EXISTS idx_anl_alert_id
    ON alert_notification_log (alert_id);

-- Optional: auto-purge old log entries after 90 days (run as a cron job or pg_cron)
-- DELETE FROM alert_notification_log WHERE sent_at < NOW() - INTERVAL '90 days';
