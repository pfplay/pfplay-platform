-- Issue #349: "유저당 활성 파티룸 1개" 불변식을 DB 차원에서 강제한다.
--
-- 배경: 그동안 이 불변식은 앱 코드(PartyroomAccessCommandService.autoExitPriorActiveRoomIfDifferent)의
-- best-effort 정리로만 유지되었고, DB 제약은 (partyroom_id, user_id) 유니크뿐이라 한 유저가 여러 방에서
-- is_active=1 을 갖는 것을 막지 못했다. 클라이언트의 재연결 핸들러 누수로 다수의 tryEnter 가 동시 발사되면
-- (스냅샷 격리로 서로의 미커밋 활성 crew 를 못 봐) auto-exit 이 모두 실패 → 유저당 활성 crew 다중 생성 →
-- getActivePartyroomByUserId 의 fetchOne 이 NonUniqueResultException 을 던져 입장 전면 불가(wedge).
--
-- 조치:
--   1) 기존 중복 활성 crew 를 유저별 1개(entered_at 최신, tie 시 crew_id 최대)만 남기고 collapse.
--   2) is_active 일 때만 user_id, 아니면 NULL 인 STORED 생성컬럼 + 유니크 → 두 번째 활성화를 DB 가 물리 거부.
--      (유니크 인덱스에서 NULL 은 서로 중복 허용되므로 비활성 crew 행은 유저당 무제한 유지 가능)

-- ── 1. 기존 중복 활성 collapse (유니크 추가 전 선행 필수) ─────────────────────────────
-- ROW_NUMBER 로 유저별 최신 활성 1개(rn=1)만 남기고 나머지(rn>1) 를 비활성화.
-- 정렬: entered_at DESC, crew_id DESC → 마이크로초 동률에도 정확히 1개만 keeper 로 결정(불변식 위반 없음).
UPDATE crew c
JOIN (
    SELECT crew_id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id
               ORDER BY entered_at DESC, crew_id DESC
           ) AS rn
    FROM crew
    WHERE is_active = 1
) ranked ON ranked.crew_id = c.crew_id
SET c.is_active      = 0,
    c.exited_at      = NOW(6),
    c.pending_exit_at = NULL
WHERE ranked.rn > 1;

-- ── 2. 생성컬럼 + 유저당 활성 유니크 (단일 ALTER = 테이블 리빌드 1회) ──────────────────
ALTER TABLE crew
    ADD COLUMN active_user_id BIGINT
        GENERATED ALWAYS AS (IF(is_active, user_id, NULL)) STORED,
    ADD CONSTRAINT uk_crew_active_user UNIQUE (active_user_id);
