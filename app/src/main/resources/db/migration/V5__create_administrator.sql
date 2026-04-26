-- V5__create_administrator.sql
-- Administration context — Administrator aggregate
-- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.2
--
-- Super admin singleton enforced by functional unique index.
-- Placeholder email/hash seeded; ApplicationReadyEvent replaces with
-- bcrypt(env.ADMIN_SEED_PASSWORD) on first boot (idempotent).

CREATE TABLE administrator (
    administrator_id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_account_id               BIGINT      NOT NULL,
    role                          VARCHAR(32) NOT NULL,
    granted_by_administrator_id   BIGINT      NULL,
    granted_at                    DATETIME    NOT NULL,
    revoked_at                    DATETIME    NULL,
    created_at                    DATETIME    DEFAULT CURRENT_TIMESTAMP,
    updated_at                    DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (administrator_id),
    UNIQUE KEY uk_administrator_user_account (user_account_id),
    CONSTRAINT fk_administrator_granted_by
        FOREIGN KEY (granted_by_administrator_id)
        REFERENCES administrator(administrator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX uk_administrator_super_admin
    ON administrator ((CASE WHEN role = 'SUPER_ADMIN' THEN 1 ELSE NULL END));

-- Seed super-admin user_account (placeholder; env replaces at boot)
INSERT INTO user_account (user_id, email, provider_type, password_hash, created_at, updated_at)
VALUES (
    1,
    '__SUPER_ADMIN_PLACEHOLDER_EMAIL__',
    'LOCAL',
    '__SUPER_ADMIN_PLACEHOLDER_HASH__',
    NOW(),
    NOW()
);

-- Seed administrator row binding super-admin to user_account 1
INSERT INTO administrator (administrator_id, user_account_id, role, granted_by_administrator_id, granted_at, created_at, updated_at)
VALUES (
    1,
    1,
    'SUPER_ADMIN',
    NULL,
    NOW(),
    NOW(),
    NOW()
);

-- Seed member row for super-admin (Party context — main-stage host needs a Member binding)
INSERT INTO member (member_id, user_account_id, authority_tier, is_profile_updated, created_at, updated_at)
VALUES (1, 1, 'FM', 0, NOW(), NOW());
