-- 가상 DJ P3-B 플레이리스트 자가갱신: watermark 컬럼 + 튜닝 키 시드.
-- ⚠️ V28 은 전역 enabled 게이트(기본 false)만 시드했다. 본 마이그레이션은 사이클 동작에 필요한
--    watermark 컬럼과 튜닝 system_config 행을 추가한다. enabled 는 여전히 false(구현 후 명시 활성화).

ALTER TABLE partyroom_virtual_dj_config
    ADD COLUMN last_self_update_at DATETIME NULL COMMENT 'P3-B 자가갱신 watermark(쿨다운 기준)';

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('vdj.playlist.self_update.cooldown_seconds', '1800', 'P3-B 자가갱신 룸별 최소 간격(초)'),
    ('vdj.playlist.self_update.min_reactions', '5', 'P3-B 갱신 트리거 새 반응 임계 K(미만이면 LLM 미호출)'),
    ('vdj.playlist.self_update.replace_per_cycle', '3', 'P3-B 사이클당 최대 교체 수 P'),
    ('vdj.playlist.self_update.recommend_count', '6', 'P3-B LLM 곡명 추천 수 N'),
    ('vdj.playlist.self_update.weight.reaction', '1000', 'P3-B score 순반응 가중치(퍼밀 ‰, 1000=1.0)'),
    ('vdj.playlist.self_update.weight.grab', '2000', 'P3-B score grab 가중치(퍼밀 ‰)'),
    ('vdj.playlist.self_update.pruned_cooldown_seconds', '3600', 'P3-B prune 곡 재추가 차단 기간(초)');
