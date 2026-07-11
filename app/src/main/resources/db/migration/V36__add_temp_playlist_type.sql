-- V36: Quick-DJ(#331) — playlist.type ENUM 에 'TEMP' 추가 (one-shot 곡 저장용 숨김 플리)
-- 기존 값('GRABLIST','PLAYLIST') 뒤에 append — 저장값/인덱스 영향 없음 (V32 MODIFY ENUM 패턴 계승)
ALTER TABLE playlist
    MODIFY COLUMN type ENUM('GRABLIST','PLAYLIST','TEMP') COMMENT '플레이리스트 타입';
