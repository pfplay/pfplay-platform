package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.shared.AvatarBodyDto;
import com.pfplaybackend.api.user.application.service.AvatarResourceQueryService;
import com.pfplaybackend.api.user.application.service.UserActivityCommandService;
import com.pfplaybackend.api.user.application.service.UserAvatarCommandService;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.value.AvatarIconUri;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserInitializeServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock MemberRepository memberRepository;
    @Mock UserProfileCommandService userProfileCommandService;
    @Mock UserActivityCommandService userActivityCommandService;
    @Mock AvatarResourceQueryService avatarResourceQueryService;
    @Mock UserAvatarCommandService userAvatarCommandService;

    @InjectMocks AdminUserInitializeService adminUserInitializeService;

    @Test
    @DisplayName("addAdminUser — 관리자 계정이 정상 생성된다")
    void addAdminUserSuccess() {
        // given
        ProfileData profile = mock(ProfileData.class);
        when(userProfileCommandService.createProfileDataForMember(any(UserId.class))).thenReturn(profile);

        MemberData member = mock(MemberData.class);
        when(memberRepository.save(any(MemberData.class))).thenReturn(member);

        AvatarBodyDto avatarBodyDto = mock(AvatarBodyDto.class);
        when(avatarBodyDto.getResourceUri()).thenReturn("https://example.com/body.png");
        when(avatarBodyDto.getCombinePositionX()).thenReturn(0);
        when(avatarBodyDto.getCombinePositionY()).thenReturn(0);
        when(avatarResourceQueryService.findAvatarBodyByUri(any())).thenReturn(avatarBodyDto);
        when(userAvatarCommandService.findAvatarIconPairWithSingleBody(any())).thenReturn(new AvatarIconUri("icon_uri"));

        // when
        UserId result = adminUserInitializeService.addAdminUser();

        // then
        assertThat(result).isNotNull();
        verify(memberRepository, atLeast(1)).save(any(MemberData.class));
        verify(userActivityCommandService).createUserActivities(any(UserId.class));
    }

    @Test
    @DisplayName("addAdminUser — 관리자 UserId가 고정값이다")
    void addAdminUserFixedUserId() {
        // given
        ProfileData profile = mock(ProfileData.class);
        when(userProfileCommandService.createProfileDataForMember(any(UserId.class))).thenReturn(profile);

        MemberData member = mock(MemberData.class);
        when(memberRepository.save(any(MemberData.class))).thenReturn(member);

        AvatarBodyDto avatarBodyDto = mock(AvatarBodyDto.class);
        when(avatarBodyDto.getResourceUri()).thenReturn("https://example.com/body.png");
        when(avatarBodyDto.getCombinePositionX()).thenReturn(0);
        when(avatarBodyDto.getCombinePositionY()).thenReturn(0);
        when(avatarResourceQueryService.findAvatarBodyByUri(any())).thenReturn(avatarBodyDto);
        when(userAvatarCommandService.findAvatarIconPairWithSingleBody(any())).thenReturn(new AvatarIconUri("icon_uri"));

        // when
        UserId result = adminUserInitializeService.addAdminUser();

        // then
        assertThat(result.getUid()).isEqualTo(1000000000000000L);
    }
}
