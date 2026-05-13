-- ─────────────────────────────────────────────────────────────────────────────
-- V3: Simplify notification_preference table
-- Remove per-user severity and cooldown columns.
-- Email on/off is the only user-configurable setting.
-- Global cooldown is managed via application.yml (notification.email.cooldown-minutes).
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE notification_preference DROP COLUMN IF EXISTS min_severity;
ALTER TABLE notification_preference DROP COLUMN IF EXISTS cooldown_override_minutes;
