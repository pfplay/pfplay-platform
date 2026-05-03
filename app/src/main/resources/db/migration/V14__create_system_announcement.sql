CREATE TABLE system_announcement (
    id                              BIGINT       NOT NULL AUTO_INCREMENT,
    type                            VARCHAR(32)  NOT NULL,
    severity                        VARCHAR(16)  NOT NULL,
    title_ko                        VARCHAR(200) NOT NULL,
    title_en                        VARCHAR(200) NOT NULL,
    message_ko                      VARCHAR(2000) NOT NULL,
    message_en                      VARCHAR(2000) NOT NULL,
    scheduled_start_at              DATETIME     NULL,
    scheduled_end_at                DATETIME     NULL,
    expires_at                      DATETIME     NULL,
    sent_at                         DATETIME     NOT NULL,
    sent_by_administrator_id        BIGINT       NOT NULL,
    maintenance_started_at          DATETIME     NULL,
    cancelled_at                    DATETIME     NULL,
    cancelled_by_administrator_id   BIGINT       NULL,
    created_at                      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_active_maintenance (type, cancelled_at, scheduled_start_at, maintenance_started_at),
    KEY idx_sent_at_desc (sent_at DESC),
    CONSTRAINT chk_maintenance_window CHECK (
        type != 'MAINTENANCE_NOTICE' OR (
            scheduled_start_at IS NOT NULL AND scheduled_end_at IS NOT NULL
            AND scheduled_end_at > scheduled_start_at)),
    CONSTRAINT fk_announcement_sent_by FOREIGN KEY (sent_by_administrator_id) REFERENCES administrator(administrator_id),
    CONSTRAINT fk_announcement_cancelled_by FOREIGN KEY (cancelled_by_administrator_id) REFERENCES administrator(administrator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
