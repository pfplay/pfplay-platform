package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.port.out.PlaylistSetupPort;
import com.pfplaybackend.api.user.application.service.UserActivityCommandService;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemporaryUserInitializeServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock GuestRepository guestRepository;
    @Mock MemberRepository memberRepository;
    @Mock UserProfileCommandService userProfileCommandService;
    @Mock UserActivityCommandService userActivityCommandService;
    @Mock PlaylistSetupPort playlistSetupPort;
    @Mock JwtService jwtService;

    @InjectMocks TemporaryUserInitializeService temporaryUserInitializeService;

    @Test
    @DisplayName("addGuest — 게스트가 정상 생성된다")
    void addGuestSuccess() {
        // given
        UserId userId = new UserId(1000000000000001L);
        ProfileData profile = mock(ProfileData.class);
        when(userProfileCommandService.createProfileDataForGuest(any(UserId.class))).thenReturn(profile);

        // when
        temporaryUserInitializeService.addGuest(userId);

        // then
        verify(guestRepository).save(any(GuestData.class));
        verify(userProfileCommandService).createProfileDataForGuest(any(UserId.class));
        // Guests do NOT get ActivityData rows in the V4 model.
        verify(userActivityCommandService, never()).createUserActivities(any(UserId.class));
    }

    @Test
    @DisplayName("addAssociateMember — AM 회원이 정상 생성된다")
    void addAssociateMemberSuccess() {
        // given
        UserId userId = new UserId(1000000000000002L);
        ProfileData profile = mock(ProfileData.class);
        when(userProfileCommandService.createProfileDataForMember(any(UserId.class))).thenReturn(profile);
        when(memberRepository.save(any(MemberData.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        temporaryUserInitializeService.addAssociateMember(userId, "AM@google.com");

        // then
        verify(memberRepository).save(any(MemberData.class));
        verify(userActivityCommandService).createUserActivities(any(UserId.class));
        verify(playlistSetupPort).createDefaultPlaylist(any(UserId.class));
    }

    @Test
    @DisplayName("upgradeMember — 회원이 FM으로 업그레이드된다")
    void upgradeMemberSuccess() {
        // given
        MemberData member = mock(MemberData.class);

        // when
        temporaryUserInitializeService.upgradeMember(member);

        // then
        verify(member).updateProfileBio("nickname", "introduction");
        verify(member).updateWalletAddress(any());
        verify(memberRepository, times(2)).save(member);
    }
}
