-- V32: 가상 DJ 봇 모델 개편 — dj_bot_count 추가, companion_floor 제거, FROZEN 제거, 봇 slot 테이블
ALTER TABLE partyroom_virtual_dj_config
    ADD COLUMN dj_bot_count INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '크루(DJ) 봇 수' AFTER target_count;
UPDATE partyroom_virtual_dj_config SET dj_bot_count = target_count;
ALTER TABLE partyroom_virtual_dj_config DROP COLUMN companion_floor;
UPDATE partyroom_virtual_dj_config SET status = 'MANAGED' WHERE status = 'FROZEN';
ALTER TABLE partyroom_virtual_dj_config
    MODIFY COLUMN status ENUM('OFF','MANAGED') NOT NULL DEFAULT 'OFF' COMMENT 'OFF/MANAGED';

CREATE TABLE virtual_dj_bot_slot (
    partyroom_id BIGINT UNSIGNED NOT NULL,
    bot_user_id  BIGINT UNSIGNED NOT NULL,
    slot_index   INT UNSIGNED    NOT NULL,
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (partyroom_id, bot_user_id),
    UNIQUE KEY uk_room_slot (partyroom_id, slot_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
