-- =====================================================
-- V34: 어드민 파티룸 행동분석 — 범위/정렬 인덱스
-- Spec: docs/superpowers/specs/2026-07-09-admin-partyroom-behavior-analytics-design.md §7
--
-- user_activity_log: partyroom_id 인덱스 신규(기존 부재).
--   ②는 event_type IN (ENTERED,EXITED)+GROUP BY date, ③은 event_type=EXITED.
--   등가 컬럼(partyroom_id, event_type)을 앞, 범위/정렬(occurred_at)을 뒤에 둔다.
-- playback: 기존 단일컬럼 playback_partyroom_id_IDX(V1)를 복합으로 대체
--   (신규가 완전 상위집합 → 중복 제거).
-- =====================================================

ALTER TABLE user_activity_log
    ADD INDEX idx_ual_partyroom_event_time (partyroom_id, event_type, occurred_at DESC);

ALTER TABLE playback
    ADD INDEX idx_playback_partyroom_time (partyroom_id, created_at DESC);

DROP INDEX playback_partyroom_id_IDX ON playback;
