-- Idempotent sample RBAC seed data for logcontroller authorization

INSERT INTO "role" (name, description)
VALUES
    ('ADMIN', 'Full platform access and management'),
    ('DEV', 'Read logs and metrics for assigned services'),
    ('VIEWER', 'Read-only logs and metrics for assigned services')
ON CONFLICT (name) DO NOTHING;

INSERT INTO "permission" (name, resource, description)
VALUES
    ('logs:read', 'logs', 'Read logs'),
    ('logs:write', 'logs', 'Ingest logs'),
    ('metrics:read', 'metrics', 'Read metrics and charts'),
    ('alerts:read', 'alerts', 'Read alerts'),
    ('alerts:write', 'alerts', 'Create and manage alerts'),
    ('services:read', 'services', 'Read accessible service list')
ON CONFLICT (name) DO NOTHING;

INSERT INTO "service" (name, description, is_active)
VALUES
    ('auth-service', 'Authentication and identity service', TRUE),
    ('payment-service', 'Payment processing service', TRUE),
    ('inventory-service', 'Inventory management service', TRUE),
    ('notification-service', 'Notification service', TRUE)
ON CONFLICT (name) DO NOTHING;

INSERT INTO "user" (id, username, email, is_active)
VALUES
    ('u-admin-root', 'admin.root', 'admin@kovanlabs.com', TRUE),
    ('u-dev-ari', 'dev.ari', 'ari@kovanlabs.com', TRUE),
    ('u-viewer-mina', 'viewer.mina', 'mina@kovanlabs.com', TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role (user_id, role_id)
SELECT 'u-admin-root', r.id FROM "role" r WHERE r.name = 'ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO user_role (user_id, role_id)
SELECT 'u-dev-ari', r.id FROM "role" r WHERE r.name = 'DEV'
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO user_role (user_id, role_id)
SELECT 'u-viewer-mina', r.id FROM "role" r WHERE r.name = 'VIEWER'
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM "role" r
JOIN "permission" p ON p.name IN ('logs:read', 'logs:write', 'metrics:read', 'alerts:read', 'alerts:write', 'services:read')
WHERE r.name = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM "role" r
JOIN "permission" p ON p.name IN ('logs:read', 'metrics:read', 'alerts:read', 'services:read')
WHERE r.name = 'DEV'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM "role" r
JOIN "permission" p ON p.name IN ('logs:read', 'metrics:read', 'alerts:read', 'services:read')
WHERE r.name = 'VIEWER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO user_service (user_id, service_id)
SELECT 'u-dev-ari', s.id
FROM "service" s
WHERE s.name IN ('auth-service', 'payment-service')
ON CONFLICT (user_id, service_id) DO NOTHING;

INSERT INTO user_service (user_id, service_id)
SELECT 'u-viewer-mina', s.id
FROM "service" s
WHERE s.name IN ('inventory-service')
ON CONFLICT (user_id, service_id) DO NOTHING;

INSERT INTO service_request (requested_by, service_name, description, status)
VALUES
    ('u-dev-ari', 'billing-service', 'Need access for payment reconciliation dashboards', 'PENDING')
ON CONFLICT DO NOTHING;
