-- V4__refactor_user_account_to_iam.sql
-- Pre-launch DROP + CREATE: inheritance(JOINED) → composition.
-- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.1.2

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS guest;
DROP TABLE IF EXISTS user_account;

SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE user_account (
    user_id         BIGINT       NOT NULL,
    email           VARCHAR(255) NOT NULL,
    provider_type   VARCHAR(16)  NOT NULL,
    password_hash   VARCHAR(255) NULL,
    last_login_at   DATETIME     NULL,
    withdrawn_at    DATETIME     NULL,
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_user_account_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE member (
    member_id            BIGINT          NOT NULL AUTO_INCREMENT,
    user_account_id      BIGINT          NOT NULL,
    authority_tier       ENUM('FM','AM','GT') NOT NULL,
    profile_id           BIGINT UNSIGNED NULL,
    is_profile_updated   BIT             NOT NULL DEFAULT 0,
    created_at           DATETIME        DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (member_id),
    UNIQUE KEY uk_member_user_account (user_account_id),
    CONSTRAINT fk_member_profile FOREIGN KEY (profile_id) REFERENCES user_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE guest (
    guest_id         BIGINT          NOT NULL AUTO_INCREMENT,
    user_account_id  BIGINT          NOT NULL,
    agent            VARCHAR(255)    NULL,
    created_at       DATETIME        DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (guest_id),
    UNIQUE KEY uk_guest_user_account (user_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
