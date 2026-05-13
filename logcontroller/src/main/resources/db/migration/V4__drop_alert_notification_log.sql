-- V4: Remove alert_notification_log table.
-- Notification audit logging has been removed from the system.
-- Cooldown deduplication is now handled in-memory only.
DROP TABLE IF EXISTS alert_notification_log;
