-- V30: Web Push 구독 테이블 + 공지 push_enabled 플래그
-- 구독: 로그인 회원만(user_id=BIGINT). endpoint UNIQUE, soft-delete(revoked_at) + 부활 upsert.
-- created_at/updated_at 정의는 BaseEntity 컨벤션(datetime default current_timestamp)과 동일.

CREATE TABLE push_subscription (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    endpoint    VARCHAR(512) NOT NULL,
    p256dh      VARCHAR(255) NOT NULL,
    auth        VARCHAR(255) NOT NULL,
    lang        VARCHAR(8)   NOT NULL DEFAULT 'EN',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    revoked_at  DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_push_subscription_endpoint (endpoint),
    KEY idx_push_subscription_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE system_announcement
    ADD COLUMN push_enabled TINYINT(1) NOT NULL DEFAULT 0;
