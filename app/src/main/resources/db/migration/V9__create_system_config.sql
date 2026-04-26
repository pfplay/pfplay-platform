-- =====================================================
-- V9: Operations context — SystemConfig (key-value 범용 저장소)
--
-- 유지보수 모드 + 향후 feature flag 수용.
-- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.6
-- =====================================================

CREATE TABLE system_config (
    config_key                        VARCHAR(64)  NOT NULL,
    config_value                      TEXT         NOT NULL,
    description                       VARCHAR(255) NULL,
    updated_by_administrator_id       BIGINT       NULL,
    updated_at                        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 유지보수 모드 기본 설정 (off by default)
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('maintenance.enabled', 'false', '유지보수 모드 활성 여부 (true일 때 일반 API 503)'),
    ('maintenance.message', '시스템 점검 중입니다. 잠시 후 다시 시도해주세요.', '유지보수 안내 메시지');
