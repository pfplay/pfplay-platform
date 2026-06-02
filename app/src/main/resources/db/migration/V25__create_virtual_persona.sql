-- P3-A: 가상 DJ 봇 페르소나(LLM 지시문) 프리셋 라이브러리
CREATE TABLE virtual_persona (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name          VARCHAR(64)     NOT NULL COMMENT '어드민 식별용 페르소나 이름',
    instruction   TEXT            NOT NULL COMMENT 'LLM system 지시문(성격/톤/장르 성향)',
    is_active     TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '비활성 시 신규 매핑 불가(기존 보존)',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_virtual_persona_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
