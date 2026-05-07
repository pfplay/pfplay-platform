-- =====================================================
-- V6: Party context — Partyroom 상태 모델 진화
--
-- - is_terminated BOOLEAN → status ENUM (ACTIVE/SUSPENDED/TERMINATED)
-- - crew_count, last_activity_at denormalized 카운터/시각
-- - display_flag (Operations 관점, 물리적으론 Party 테이블)
-- =====================================================

-- 1. 새 컬럼 추가
ALTER TABLE partyroom
    ADD COLUMN status ENUM('ACTIVE','SUSPENDED','TERMINATED') NOT NULL DEFAULT 'ACTIVE' AFTER is_terminated,
    ADD COLUMN crew_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_activity_at DATETIME NULL,
    ADD COLUMN display_flag ENUM('NORMAL','FEATURED','HIDDEN') NOT NULL DEFAULT 'NORMAL';

-- 2. is_terminated → status 데이터 이관 (ACTIVE는 default라 별도 UPDATE 불필요)
UPDATE partyroom SET status = 'TERMINATED' WHERE is_terminated = 1;

-- 3. crew_count 초기 계산 (활성 crew만)
UPDATE partyroom p
SET crew_count = (
    SELECT COUNT(*) FROM crew c
    WHERE c.partyroom_id = p.partyroom_id AND c.is_active = 1
);

-- 4. last_activity_at 초기값 (방금 추가한 컬럼이라 모두 NULL)
UPDATE partyroom SET last_activity_at = COALESCE(updated_at, created_at);

-- 5. 기존 컬럼 제거
ALTER TABLE partyroom DROP COLUMN is_terminated;

-- 6. 인덱스 (목록 쿼리 최적화)
CREATE INDEX idx_partyroom_status_activity ON partyroom (status, last_activity_at DESC);
CREATE INDEX idx_partyroom_display_flag ON partyroom (display_flag);
