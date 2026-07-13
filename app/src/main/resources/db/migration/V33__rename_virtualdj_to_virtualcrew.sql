-- V33: virtualdj → virtualcrew 바운디드 컨텍스트 리네임 (테이블·설정키)
RENAME TABLE partyroom_virtual_dj_config TO partyroom_virtual_crew_config;
RENAME TABLE virtual_dj_bot_slot TO virtual_crew_bot_slot;
UPDATE system_config SET config_key = CONCAT('vcrew.', SUBSTRING(config_key FROM 5)) WHERE config_key LIKE 'vdj.%';
UPDATE system_config SET config_key = CONCAT('virtualcrew.', SUBSTRING(config_key FROM 11)) WHERE config_key LIKE 'virtualdj.%';
