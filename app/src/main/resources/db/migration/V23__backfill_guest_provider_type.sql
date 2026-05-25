-- =====================================================
-- V23: 게스트 user_account 의 provider_type 정정 (issue #268)
--
-- 게스트(임시 유저)는 OAuth 를 거치지 않으므로 provider_type 에 들어가 있던 'GOOGLE'
-- 은 의미상 틀린 placeholder 였다. ProviderType 에 GUEST 값을 도입하고, guest 테이블에
-- 레코드가 있는 user_account 행(= 게스트)을 GUEST 로 backfill 한다.
-- =====================================================

UPDATE user_account
SET provider_type = 'GUEST'
WHERE user_id IN (SELECT user_account_id FROM guest);
