-- =====================================================
-- V30: 빈-아바타 회원 백필 (#312)
--
-- Why:
--   실제 OAuth 회원가입 경로(MemberSignService.initializeNewMember →
--   UserProfileCommandService.createProfileDataForMember)가 디폴트 아바타를
--   시드하지 않아, 신규 회원의 avatar body/face/icon 이 모두 '' 로 남았다
--   (ProfileData.@PrePersist applyDefaults 가 null→'' 보정). 게스트/슈퍼어드민/
--   임시 풀멤버는 buildDefaultAvatarProfile 로 디폴트를 받지만 회원만 누락.
--   특히 모바일 가입자는 아바타 편집 진입이 막혀(web #393 의도된 desktop-only)
--   영구 빈-아바타로 남는다. 코드 fix(createProfileDataForMember → 디폴트 시드)와
--   함께 이미 빈-아바타로 박힌 기존 회원을 보정한다.
--
-- 무엇으로 채우나:
--   신규 회원이 코드 fix 후 받는 디폴트와 '동일'해야 한다. 현재 디폴트 바디는
--   ava_body_basic_003(유령, is_combinable=0)이므로 SINGLE_BODY 규약을 따른다
--   (V20 과 동일):
--     avatar_body_uri          = 003 바디 URL
--     avatar_face_uri          = '' (SINGLE_BODY: face 합성 없음)
--     avatar_icon_uri          = 003 body 페어 아이콘
--     avatar_composition_type  = SINGLE_BODY
--     face_source_type         = INTERNAL_IMAGE (빈-아바타 회원은 NULL 일 수 있어 명시 보정)
--     combine_positionx/y      = 0,0 (003 시드값)
--
-- 식별 조건:
--   avatar_body_uri = '' 인 user_profile 만 대상. 빈 바디는 회원 누락 경로의
--   고유 시그니처다(게스트/슈퍼어드민은 항상 디폴트 바디를 받으므로 빈 바디가
--   존재할 수 없다). 사용자가 설정한 아바타(비어있지 않은 바디)는 건드리지 않는다.
-- =====================================================

UPDATE user_profile up
   SET up.avatar_body_uri          = 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_003.png?alt=media',
       up.avatar_face_uri          = '',
       up.avatar_icon_uri          = 'https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_icon%2Fava_icon_body_basic_003.png?alt=media',
       up.avatar_composition_type  = 'SINGLE_BODY',
       up.face_source_type         = 'INTERNAL_IMAGE',
       up.combine_positionx        = 0,
       up.combine_positiony        = 0
 WHERE up.avatar_body_uri = '';
