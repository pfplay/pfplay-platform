-- Presence grace window: distinguish "client disconnected briefly" (PENDING_EXIT)
-- from "client confirmed gone" (OFFLINE). DB row is the source of truth; Redis
-- TTL key drives the grace timer (with cron safety net for app-restart durability).

ALTER TABLE crew
    ADD COLUMN pending_exit_at DATETIME(6) NULL,
    ADD INDEX idx_crew_pending_exit (pending_exit_at);

INSERT INTO system_config (config_key, config_value, description) VALUES
    ('presence.dj_grace_seconds',       '30', '현재 DJ가 끊겼을 때 OFFLINE 판정까지의 유예(초)'),
    ('presence.listener_grace_seconds', '10', '일반 listener가 끊겼을 때 OFFLINE 판정까지의 유예(초)');
