-- 가상 DJ P3 채팅 (Chunk 3) 런타임 설정 시드.
-- SystemConfigCache 가 fail-open 으로 읽으므로 누락돼도 코드 DEFAULT 로 폴백되지만,
-- 어드민/SQL 에서 토글/조정할 수 있도록 행을 미리 심는다. vdj.chat.enabled 는 전역 kill switch.
-- ⚠️ 기본 잠금(false): 봇 채팅은 외부 LLM 키(ANTHROPIC_API_KEY) + 제품 승인(AI 비공개 정책)이
--    준비된 뒤 명시적으로 켠다. 활성화: UPDATE system_config SET config_value='true'
--    WHERE config_key='vdj.chat.enabled';  (SystemConfigCache 30s TTL → 재시작 불필요)

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('vdj.chat.enabled',                'false', 'P3 봇 채팅 전역 kill switch(기본 잠금 — 키+승인 후 활성화)'),
    ('vdj.chat.trigger.probability',    '12',   '사람 메시지당 봇 응답 시도 확률(%)'),
    ('vdj.chat.room.cooldown.seconds',  '30',   '방별 봇 응답 최소 간격(초). 게이트 SETNX 키 TTL 겸용'),
    ('vdj.chat.context.size',           '20',   'LLM 주입 최근 사람 메시지 수'),
    ('vdj.chat.output.max.tokens',      '256',  '봇 응답 최대 토큰');
