-- E/#6 (issue #231) 일회성 음수 카운터 보정.
-- 빠른 연타 race 로 PLAYBACK_AGGREGATION 의 like/dislike/grab 카운터가 음수로 drift 한
-- 기존 행을 0 으로 floor 한다. 근본 원인(history 미락 + delta staleness)은 같은 PR 에서
-- PESSIMISTIC_WRITE 락 + native GREATEST(0,...) floor 가드로 차단된다. 본 마이그레이션은
-- 이미 음수로 떨어진 잔존 데이터만 일회성 보정한다.
-- 주의: playback_aggregation.updated_at 은 ON UPDATE CURRENT_TIMESTAMP 이므로 보정 대상
-- 행의 updated_at 이 갱신된다 — 카운터 무결성 회복이 우선이므로 이 side-effect 는 허용한다.
-- history↔counter 전면 재유도(re-derivation)는 본 PR 범위 밖(별도 검토).

UPDATE playback_aggregation
SET like_count    = GREATEST(0, like_count),
    dislike_count = GREATEST(0, dislike_count),
    grab_count    = GREATEST(0, grab_count)
WHERE like_count < 0
   OR dislike_count < 0
   OR grab_count < 0;
