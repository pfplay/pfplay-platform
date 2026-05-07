package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileCommandServiceTest {

    private static final String DEFAULT_BODY = "default-body";

    @Mock UserAvatarQueryService userAvatarQueryService;
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

        // when
        ProfileData profile = userProfileCommandService.createProfileDataForGuest(userId);

        // then
        assertThat(profile.getUserId()).isEqualTo(userId);
        assertThat(profile.getNicknameValue()).startsWith("Guest_");
        assertThat(profile.getAvatarSetting().getAvatarBodyUri().getValue()).isEqualTo(DEFAULT_BODY);
    }

    @Test
    @DisplayName("createProfileDataForMember — 멤버 프로필이 생성된다")
    void createProfileDataForMemberCreatesMinimalProfile() {
        // given
        UserId userId = new UserId(1L);

        // when
        ProfileData profile = userProfileCommandService.createProfileDataForMember(userId);

        // then
        assertThat(profile.getUserId()).isEqualTo(userId);
    }
}
