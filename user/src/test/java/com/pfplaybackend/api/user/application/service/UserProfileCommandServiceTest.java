package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.user.adapter.out.persistence.UserProfileRepository;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.user.domain.value.Nickname;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileCommandServiceTest {

    private static final String DEFAULT_BODY = "default-body";

    @Mock UserAvatarQueryService userAvatarQueryService;
    @Mock UserProfileRepository userProfileRepository;
    @InjectMocks UserProfileCommandService userProfileCommandService;

    @Test
    @DisplayName("createProfileDataForGuest — 게스트 프로필이 기본 아바타로 생성된다")
    void createProfileDataForGuestCreatesWithDefaultAvatar() {
        // given
        UserId userId = new UserId(1L);
        AvatarBodyDto defaultBody = AvatarBodyDto.builder()
                .id(1L).name("default").resourceUri(DEFAULT_BODY)
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .combinable(true).defaultSetting(true)
                .combinePositionX(50).combinePositionY(100)
                .build();
        when(userAvatarQueryService.getDefaultAvatarBody()).thenReturn(defaultBody);
        when(userAvatarQueryService.getDefaultAvatarBodyUri()).thenReturn(new AvatarBodyUri(DEFAULT_BODY));
        when(userAvatarQueryService.getDefaultAvatarFaceUri()).thenReturn(new AvatarFaceUri("default-face"));
        when(userAvatarQueryService.getDefaultAvatarIconUri()).thenReturn(new AvatarIconUri("default-icon"));
        when(userProfileRepository.existsByNickname(any(Nickname.class))).thenReturn(false);

        // when
        ProfileData profile = userProfileCommandService.createProfileDataForGuest(userId);

        // then
        assertThat(profile.getUserId()).isEqualTo(userId);
        assertThat(profile.getNicknameValue()).startsWith("Guest_");
        assertThat(profile.getAvatarSetting().getAvatarBodyUri().getValue()).isEqualTo(DEFAULT_BODY);
    }

    @Test
    @DisplayName("createProfileDataForGuest — 닉네임 충돌 시 새 후보로 재시도한다")
    void createProfileDataForGuestRetriesOnCollision() {
        // given
        UserId userId = new UserId(2L);
        AvatarBodyDto defaultBody = AvatarBodyDto.builder()
                .id(1L).name("default").resourceUri(DEFAULT_BODY)
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .combinable(true).defaultSetting(true)
                .combinePositionX(50).combinePositionY(100)
                .build();
        when(userAvatarQueryService.getDefaultAvatarBody()).thenReturn(defaultBody);
        when(userAvatarQueryService.getDefaultAvatarBodyUri()).thenReturn(new AvatarBodyUri(DEFAULT_BODY));
        when(userAvatarQueryService.getDefaultAvatarFaceUri()).thenReturn(new AvatarFaceUri("default-face"));
        when(userAvatarQueryService.getDefaultAvatarIconUri()).thenReturn(new AvatarIconUri("default-icon"));
        when(userProfileRepository.existsByNickname(any(Nickname.class)))
                .thenReturn(true)
                .thenReturn(false);

        // when
        ProfileData profile = userProfileCommandService.createProfileDataForGuest(userId);

        // then
        assertThat(profile.getNicknameValue()).startsWith("Guest_");
        verify(userProfileRepository, times(2)).existsByNickname(any(Nickname.class));
    }

    @Test
    @DisplayName("createProfileDataForGuest — 기본 바디가 combinable=false 면 SINGLE_BODY 모드로 face 미할당, icon은 body 페어")
    void createProfileDataForGuestNonCombinableBodyYieldsSingleBody() {
        // given: default body is the ghost body (combinable=false)
        UserId userId = new UserId(10L);
        AvatarBodyDto ghostBody = AvatarBodyDto.builder()
                .id(3L).name("ava_body_basic_003").resourceUri("ghost-body-uri")
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .combinable(false).defaultSetting(true)
                .combinePositionX(0).combinePositionY(0)
                .build();
        when(userAvatarQueryService.getDefaultAvatarBody()).thenReturn(ghostBody);
        when(userAvatarQueryService.getDefaultAvatarBodyUri()).thenReturn(new AvatarBodyUri("ghost-body-uri"));
        when(userAvatarQueryService.getDefaultAvatarBodyPairIconUri()).thenReturn(new AvatarIconUri("ghost-body-pair-icon-uri"));
        when(userProfileRepository.existsByNickname(any(Nickname.class))).thenReturn(false);

        // when
        ProfileData profile = userProfileCommandService.createProfileDataForGuest(userId);

        // then
        assertThat(profile.getUserId()).isEqualTo(userId);
        assertThat(profile.getAvatarSetting().getAvatarCompositionType()).isEqualTo(AvatarCompositionType.SINGLE_BODY);
        assertThat(profile.getAvatarSetting().getAvatarBodyUri().getValue()).isEqualTo("ghost-body-uri");
        assertThat(profile.getAvatarSetting().getAvatarFaceUri().getValue()).isEmpty();
        assertThat(profile.getAvatarSetting().getAvatarIconUri().getValue()).isEqualTo("ghost-body-pair-icon-uri");
        assertThat(profile.getAvatarSetting().getCombinePositionX()).isZero();
        assertThat(profile.getAvatarSetting().getCombinePositionY()).isZero();
    }

    @Test
    @DisplayName("createProfileDataForMember — 멤버 프로필이 기본 아바타로 생성된다(닉네임은 온보딩에서 별도 설정)")
    void createProfileDataForMemberCreatesWithDefaultAvatar() {
        // given: 게스트/슈퍼어드민과 동일한 디폴트 아바타 시드.
        // #393 모바일 온보딩 설계가 전제한 '서버 자동 셋팅'을 회원 경로에서도 충족해야 한다.
        UserId userId = new UserId(1L);
        AvatarBodyDto defaultBody = AvatarBodyDto.builder()
                .id(1L).name("default").resourceUri(DEFAULT_BODY)
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .combinable(true).defaultSetting(true)
                .combinePositionX(50).combinePositionY(100)
                .build();
        when(userAvatarQueryService.getDefaultAvatarBody()).thenReturn(defaultBody);
        when(userAvatarQueryService.getDefaultAvatarBodyUri()).thenReturn(new AvatarBodyUri(DEFAULT_BODY));
        when(userAvatarQueryService.getDefaultAvatarFaceUri()).thenReturn(new AvatarFaceUri("default-face"));
        when(userAvatarQueryService.getDefaultAvatarIconUri()).thenReturn(new AvatarIconUri("default-icon"));

        // when
        ProfileData profile = userProfileCommandService.createProfileDataForMember(userId);

        // then: 아바타 3종이 비어있지 않고 디폴트로 채워진다 (크루 목록 렌더 가능).
        assertThat(profile.getUserId()).isEqualTo(userId);
        assertThat(profile.getAvatarSetting().getAvatarBodyUri().getValue()).isEqualTo(DEFAULT_BODY);
        assertThat(profile.getAvatarSetting().getAvatarFaceUri().getValue()).isEqualTo("default-face");
        assertThat(profile.getAvatarSetting().getAvatarIconUri().getValue()).isEqualTo("default-icon");
        assertThat(profile.getAvatarSetting().getAvatarCompositionType()).isEqualTo(AvatarCompositionType.BODY_WITH_FACE);
        // 닉네임은 회원 온보딩(updateProfileBio)에서 설정 — 생성 시점엔 비어있다.
        assertThat(profile.getNicknameValue()).isNull();
    }
}
