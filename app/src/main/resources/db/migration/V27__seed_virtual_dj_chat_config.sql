-- 가상 DJ P3 채팅 (Chunk 3) 런타임 설정 시드.
-- SystemConfigCache 가 fail-open 으로 읽으므로 누락돼도 코드 DEFAULT 로 폴백되지만,
-- 어드민에서 토글/조정할 수 있도록 행을 미리 심는다. vdj.chat.enabled 는 전역 kill switch.

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('vdj.chat.enabled',                'true', 'P3 봇 채팅 전역 kill switch'),
    ('vdj.chat.trigger.probability',    '12',   '사람 메시지당 봇 응답 시도 확률(%)'),
    ('vdj.chat.room.cooldown.seconds',  '30',   '방별 봇 응답 최소 간격(초). 게이트 SETNX 키 TTL 겸용'),
    ('vdj.chat.context.size',           '20',   'LLM 주입 최근 사람 메시지 수'),
    ('vdj.chat.output.max.tokens',      '256',  '봇 응답 최대 토큰');
