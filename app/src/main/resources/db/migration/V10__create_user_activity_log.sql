-- =====================================================
-- V10: Administration context — user_activity_log
-- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.7
-- Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §3
-- Plan: docs/superpowers/plans/2026-04-28-admin-platform-pr12a.md Task 1
--
-- Append-only audit timeline. 월별 RANGE 파티셔닝.
-- 모든 user_account 참조는 loose ref (cross-context, no FK).
-- p_future MAXVALUE는 partition 자동 생성 배치 부재 시 안전망.
--
-- PRIMARY KEY는 (log_id, occurred_at) — MySQL partitioned table 요구사항.
-- log_id는 AUTO_INCREMENT로 글로벌 유일하므로 JPA 매핑은 log_id 단독 @Id로 처리
-- (Hibernate가 @IdClass + IDENTITY 조합을 거부함). UserActivityLogData Javadoc 참조.
-- =====================================================

CREATE TABLE user_activity_log (
    log_id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_account_id   BIGINT       NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    partyroom_id      BIGINT       NULL,
    metadata          JSON         NULL,
    occurred_at       DATETIME     NOT NULL,
    PRIMARY KEY (log_id, occurred_at),
    INDEX idx_ual_user_time (user_account_id, occurred_at DESC),
    INDEX idx_ual_event_time (event_type, occurred_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
PARTITION BY RANGE (TO_DAYS(occurred_at)) (
    PARTITION p202604 VALUES LESS THAN (TO_DAYS('2026-05-01')),
    PARTITION p202605 VALUES LESS THAN (TO_DAYS('2026-06-01')),
    PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
    PARTITION p202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
    PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
    PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);
