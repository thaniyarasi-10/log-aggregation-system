-- PostgreSQL RBAC schema for logcontroller authorization
-- Requires: PostgreSQL 14+

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS "user" (
    id            VARCHAR(50) PRIMARY KEY,
    username      VARCHAR(50)  UNIQUE NOT NULL,
    email         VARCHAR(255) UNIQUE NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS "role" (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "permission" (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) UNIQUE NOT NULL,
    resource    VARCHAR(50)  NOT NULL,
    description TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "service" (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) UNIQUE NOT NULL,
    description TEXT,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_role (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     VARCHAR(50) NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES "role"(id) ON DELETE CASCADE,
    assigned_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_permission (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id       UUID NOT NULL REFERENCES "role"(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES "permission"(id) ON DELETE CASCADE,
    assigned_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS user_service (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     VARCHAR(50) NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    service_id  UUID NOT NULL REFERENCES "service"(id) ON DELETE CASCADE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, service_id)
);

CREATE INDEX IF NOT EXISTS idx_user_role_user_id ON user_role(user_id);
CREATE INDEX IF NOT EXISTS idx_user_role_role_id ON user_role(role_id);
CREATE INDEX IF NOT EXISTS idx_role_permission_role_id ON role_permission(role_id);
CREATE INDEX IF NOT EXISTS idx_role_permission_permission_id ON role_permission(permission_id);
CREATE INDEX IF NOT EXISTS idx_user_service_user_id ON user_service(user_id);
CREATE INDEX IF NOT EXISTS idx_user_service_service_id ON user_service(service_id);
