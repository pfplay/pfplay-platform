ALTER TABLE playlist
    ADD COLUMN last_played_track_id bigint unsigned NULL COMMENT '재생 커서 — 마지막 재생 트랙 id';
