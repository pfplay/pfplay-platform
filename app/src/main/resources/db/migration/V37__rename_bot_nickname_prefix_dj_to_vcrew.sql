-- V37: 가상 크루 봇 닉네임 접두어 DJ_ → VCREW_ (기존 봇만).
--
-- 신규 봇은 VirtualUserPoolService.generateBotNickname 에서 이미 VCREW_ 로 생성한다(코드 변경).
-- 이 마이그레이션은 기존에 생성된 봇들의 닉네임을 함께 맞춘다.
--
-- 스코프: 봇 닉네임 생성식 = "DJ_" + UUID 12 소문자 hex. 정확히 그 패턴만 치환하여
--         실사용자의 "DJ_..." 닉네임(예: DJ_Master) 오염을 방지한다.
-- 길이: "DJ_"(3) + hex(12) = 15 → "VCREW_"(6) + hex(12) = 18 ≤ user_profile.nickname VARCHAR(20).
-- UNIQUE(uk_user_profile_nickname): hex 접미가 고유하고 VCREW_ 접두는 신규라 충돌 없음.
UPDATE user_profile
SET nickname = CONCAT('VCREW_', SUBSTRING(nickname, 4))
WHERE nickname REGEXP '^DJ_[0-9a-f]{12}$';
