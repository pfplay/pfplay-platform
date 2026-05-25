-- =====================================================
-- V22: 죽은 maintenance.* system_config 키 제거 (issue #267)
--
-- 백엔드 점검 게이트(MaintenanceModeFilter)를 ACTIVE 인 system_announcement 점검을
-- 단일 진실원천으로 재배선(MaintenanceGate). 기존 V9 시드 'maintenance.enabled' /
-- 'maintenance.message' 는 write 하는 코드가 없어 백엔드 503 게이트를 한 번도 켜지
-- 못한 dead 키였다. 코드에서 제거됨에 맞춰 시드 행도 정리해 system_config 가 권위 있는
-- 것처럼 보이는 orphan 행을 남기지 않도록 한다.
-- =====================================================

DELETE FROM system_config WHERE config_key IN ('maintenance.enabled', 'maintenance.message');
