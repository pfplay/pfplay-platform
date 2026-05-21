-- =====================================================
-- V20: Guest default avatar body → 유령 바디 (ava_body_basic_003)
--
-- Why:
--   게스트의 기본 아바타 바디를 ava_body_basic_001 → ava_body_basic_003
--   (combinable=0, "유령 바디") 으로 일괄 전환. 향후 신규 게스트는
--   is_default_setting 플래그가 가리키는 row 를 자동으로 받으므로,
--   본 마이그레이션에서 플래그를 이동시키면 코드 변경 없이도 신규 게스트는
--   유령 바디를 받는다. 동시 적용되는 코드 변경(combinable-aware
--   createProfileDataForGuest) 은 SINGLE_BODY 모드로 face 합성을
--   끊는다.
--
-- 기존 게스트 row 정합성:
--   ava_body_basic_003 은 is_combinable=0 (face 합성 불가) 이므로,
--   이미 001 디폴트로 박혀있던 게스트는 avatar_composition_type 도
--   BODY_WITH_FACE → SINGLE_BODY 로 함께 전환되어야 한다. 그렇지
--   않으면 "유령 바디에 face overlay" 라는 도메인 모순이 발생한다.
--   SINGLE_BODY 규약(UserAvatarCommandService.setUserAvatar 참고):
--     avatar_face_uri = '' (empty)
--     avatar_icon_uri = body 페어 아이콘
--     combine_position_x/y = body 의 시드값 (003 은 0,0)
--     face_source_type 은 untouched (INTERNAL_IMAGE 유지)
--
-- 식별 조건:
--   guest.profile_id (FK) 로 user_profile.id 와 INNER JOIN 후,
--   현재 avatar_body_uri 가 001 시드 URL 인 게스트만 대상.
--   사용자가 명시적으로 다른 바디로 바꿔놓은 게스트는 건드리지 않는다.
-- =====================================================

-- 1. avatar_body_resource: is_default_setting 플래그를 001 → 003 으로 이동
UPDATE avatar_body_resource
   SET is_default_setting = 0
 WHERE name = 'ava_body_basic_001';

UPDATE avatar_body_resource
   SET is_default_setting = 1
 WHERE name = 'ava_body_basic_003';

-- 2. 기존 게스트 user_profile 일괄 업데이트
--    avatar_body_uri 가 001 시드 URL 이고 guest 테이블에 연결된 row 만 대상.
UPDATE user_profile up
  JOIN guest g ON g.profile_id = up.id
   SET up.avatar_body_uri          = 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_003.png?alt=media',
       up.avatar_face_uri          = '',
       up.avatar_icon_uri          = 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_basic_003.png?alt=media',
       up.avatar_composition_type  = 'SINGLE_BODY',
       up.combine_positionx        = 0,
       up.combine_positiony        = 0
 WHERE up.avatar_body_uri = 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media';
