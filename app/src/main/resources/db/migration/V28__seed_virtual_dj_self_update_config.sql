-- 가상 DJ P3-B 플레이리스트 자가갱신 forward-gate 시드.
-- SystemConfigCache 가 fail-open 으로 읽으므로 누락돼도 코드 DEFAULT(false) 로 폴백되지만,
-- 어드민 채팅/자가갱신 설정 패널에서 토글할 수 있도록 행을 미리 심는다.
-- ⚠️ 기본 잠금(false): P3-B 자가갱신 로직 구현 후 명시적으로 켠다.

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('vdj.playlist.self_update.enabled', 'false',
     'P3-B 봇 플레이리스트 자가갱신 전역 토글(기본 잠금 — 구현 후 활성화)');
