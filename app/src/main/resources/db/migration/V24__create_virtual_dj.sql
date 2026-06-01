-- =====================================================
-- V24: 가상 사용자 능동 디제잉 (P2)
--
-- 봇(가상 사용자) 플래그, 송 팩 테이블, 파티룸별 가상 DJ 설정 테이블,
-- 플레이리스트↔송 팩 연결 컬럼을 추가한다.
-- =====================================================

ALTER TABLE user_account
    ADD COLUMN is_dummy TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '가상 사용자(봇) 여부';

ALTER TABLE playlist
    ADD COLUMN source_song_pack_id BIGINT UNSIGNED NULL
        COMMENT '이 playlist 가 복사된 송 팩 (봇 전용)';

CREATE TABLE virtual_song_pack (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100)    NOT NULL,
    description VARCHAR(255)    NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_virtual_song_pack_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE virtual_song_pack_track (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    song_pack_id     BIGINT UNSIGNED NOT NULL,
    order_number     INT UNSIGNED    NOT NULL,
    link_id          VARCHAR(255)    NOT NULL COMMENT 'YouTube videoId',
    name             VARCHAR(255)    NOT NULL,
    duration         VARCHAR(255)    NOT NULL COMMENT 'MM:SS, playbackTimeLimit 사전검증됨',
    thumbnail_image  VARCHAR(255)    NULL,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_vspt_song_pack_id (song_pack_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE partyroom_virtual_dj_config (
    partyroom_id     BIGINT UNSIGNED NOT NULL,
    status           ENUM('OFF','MANAGED','FROZEN') NOT NULL DEFAULT 'OFF' COMMENT 'OFF/MANAGED/FROZEN',
    target_count     INT UNSIGNED    NOT NULL DEFAULT 2,
    companion_floor  INT UNSIGNED    NOT NULL DEFAULT 1,
    song_pack_id     BIGINT UNSIGNED NULL,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (partyroom_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
