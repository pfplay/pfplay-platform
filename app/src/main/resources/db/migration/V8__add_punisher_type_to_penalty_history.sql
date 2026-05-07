-- =====================================================
-- V8: Party context — crew_penalty_history에 punisher_type 추가
-- Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.5
-- Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr9-design.md §3
-- Plan: docs/superpowers/plans/2026-04-28-admin-platform-pr9.md Task 1
--
-- 어드민이 부과한 페널티를 구분.
-- 어드민 정체(administrator_id)는 partyroom_admin_action에 별도 기록.
-- correlation은 partyroom_admin_action.metadata.crew_penalty_history_id.
-- punisher_crew_id는 V1부터 nullable이라 ALTER 불필요 — admin 부과 시 NULL.
-- crew_block_history는 user-to-user 차단 의미라 본 PR 범위에서 제외 (admin 무관).
-- =====================================================

ALTER TABLE crew_penalty_history
    ADD COLUMN punisher_type ENUM('CREW','ADMIN') NOT NULL DEFAULT 'CREW' AFTER punisher_crew_id;
