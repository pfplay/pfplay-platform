-- P3-A: 봇(가상멤버) ↔ 페르소나 매핑. 행 존재 = 그 봇은 채팅 참여, 행 없음 = 침묵.
CREATE TABLE bot_persona_assignment (
    bot_user_id   BIGINT UNSIGNED NOT NULL COMMENT '봇 user_account id(앱가드 참조, 무FK)',
    persona_id    BIGINT UNSIGNED NOT NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (bot_user_id),
    CONSTRAINT fk_bpa_persona FOREIGN KEY (persona_id) REFERENCES virtual_persona(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
