package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.command.SignMemberCommand;
import com.pfplaybackend.api.user.application.dto.result.MemberSignResult;
import com.pfplaybackend.api.user.application.port.out.OAuth2RedirectPort;
import com.pfplaybackend.api.user.application.port.out.PlaylistSetupPort;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.event.MemberRegisteredEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberSignServiceTest {

    private static final String GOOGLE = "google";
    private static final String CALLBACK_PATH = "/callback";
    private static final String EMAIL = "test@example.com";

    @Mock OAuth2RedirectPort oauth2RedirectPort;
    @Mock UserAccountRepository userAccountRepository;
    @Mock MemberRepository memberRepository;
    @Mock UserProfileCommandService userProfileCommandService;
    @Mock UserActivityCommandService userActivityCommandService;
    @Mock PlaylistSetupPort playlistSetupPort;
    @Mock ApplicationEventPublisher applicationEventPublisher;
    @InjectMocks MemberSignService memberSignService;

    @Test
    @DisplayName("getMemberOrCreate — UserAccount 미존재 시 UserAccount/Member 영속화 + 프로필/플레이리스트/이벤트 초기화 부수효과를 모두 수행한다")
    void getMemberOrCreatePersistsBothWhenUserAccountMissing() {
        // given
        when(userAccountRepository.findByEmailAndProviderType(EMAIL, ProviderType.GOOGLE))
                .thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccountData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(memberRepository.findByUserAccountId(any(Long.class)))
                .thenReturn(Optional.empty());
        when(memberRepository.save(any(MemberData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userProfileCommandService.createProfileDataForMember(any(UserId.class)))
                .thenReturn(mock(ProfileData.class));

        // when
        MemberData result = memberSignService.getMemberOrCreate(EMAIL, ProviderType.GOOGLE);

        // then
        assertThat(result).isNotNull();

        ArgumentCaptor<UserAccountData> uaCaptor = ArgumentCaptor.forClass(UserAccountData.class);
        verify(userAccountRepository).save(uaCaptor.capture());
        UserAccountData savedAccount = uaCaptor.getValue();
        assertThat(savedAccount.getEmail()).isEqualTo(EMAIL);
        assertThat(savedAccount.getProviderType()).isEqualTo(ProviderType.GOOGLE);
        assertThat(savedAccount.getLastLoginAt()).isNotNull();

        ArgumentCaptor<MemberData> memberCaptor = ArgumentCaptor.forClass(MemberData.class);
        verify(memberRepository).save(memberCaptor.capture());
        MemberData savedMember = memberCaptor.getValue();
        assertThat(savedMember.getUserAccountId()).isEqualTo(savedAccount.getUserId().getUid());

        // Side-effects on the new-Member path (matches pre-V4 behavior).
        verify(userProfileCommandService).createProfileDataForMember(savedAccount.getUserId());
        verify(playlistSetupPort).createDefaultPlaylist(savedAccount.getUserId());
        verify(applicationEventPublisher).publishEvent(any(MemberRegisteredEvent.class));
    }

    @Test
    @DisplayName("getMemberOrCreate — UserAccount와 Member가 모두 있으면 어느 것도 새로 저장하지 않으며 부수효과도 발생시키지 않는다")
    void getMemberOrCreateReturnsExistingWhenBothPresent() {
        // given
        UserAccountData existingAccount = UserAccountData.createForSocial(
                new UserId(123L), EMAIL, ProviderType.GOOGLE);
        MemberData existingMember = mock(MemberData.class);

        when(userAccountRepository.findByEmailAndProviderType(EMAIL, ProviderType.GOOGLE))
                .thenReturn(Optional.of(existingAccount));
        when(memberRepository.findByUserAccountId(123L))
                .thenReturn(Optional.of(existingMember));

        // when
        MemberData result = memberSignService.getMemberOrCreate(EMAIL, ProviderType.GOOGLE);

        // then
        assertThat(result).isSameAs(existingMember);
        verify(userAccountRepository, never()).save(any(UserAccountData.class));
        verify(memberRepository, never()).save(any(MemberData.class));
        // last_login_at was still recorded on the existing entity (flush at tx end)
        assertThat(existingAccount.getLastLoginAt()).isNotNull();

        // No new-Member side-effects when Member already exists.
        verify(userProfileCommandService, never()).createProfileDataForMember(any(UserId.class));
        verify(playlistSetupPort, never()).createDefaultPlaylist(any(UserId.class));
        verify(applicationEventPublisher, never()).publishEvent(any(MemberRegisteredEvent.class));
    }

    @Test
    @DisplayName("getMemberOrCreate — UserAccount는 있지만 Member가 없으면 Member만 새로 저장하면서 부수효과를 수행한다 (복구 시나리오)")
    void getMemberOrCreateRecoversMissingMember() {
        // given
        UserAccountData existingAccount = UserAccountData.createForSocial(
                new UserId(456L), EMAIL, ProviderType.GOOGLE);

        when(userAccountRepository.findByEmailAndProviderType(EMAIL, ProviderType.GOOGLE))
                .thenReturn(Optional.of(existingAccount));
        when(memberRepository.findByUserAccountId(456L))
                .thenReturn(Optional.empty());
        when(memberRepository.save(any(MemberData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userProfileCommandService.createProfileDataForMember(any(UserId.class)))
                .thenReturn(mock(ProfileData.class));

        // when
        MemberData result = memberSignService.getMemberOrCreate(EMAIL, ProviderType.GOOGLE);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getUserAccountId()).isEqualTo(456L);
        verify(userAccountRepository, never()).save(any(UserAccountData.class));
        verify(memberRepository).save(any(MemberData.class));

        // The "UA exists, Member missing" path is also a new-Member path,
        // so onboarding side-effects fire here too.
        verify(userProfileCommandService).createProfileDataForMember(existingAccount.getUserId());
        verify(playlistSetupPort).createDefaultPlaylist(existingAccount.getUserId());
        verify(applicationEventPublisher).publishEvent(any(MemberRegisteredEvent.class));
    }

    @Test
    @DisplayName("getOrCreateMemberFor — Member 미존재 시 새 Member를 저장하고 onboarding 부수효과를 수행하되 recordLogin을 호출하지 않는다")
    void getOrCreateMemberForCreatesMemberWithoutRecordingLogin() {
        // given
        UserAccountData existingAccount = UserAccountData.createForSocial(
                new UserId(789L), EMAIL, ProviderType.GOOGLE);
        when(memberRepository.findByUserAccountId(789L)).thenReturn(Optional.empty());
        when(memberRepository.save(any(MemberData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userProfileCommandService.createProfileDataForMember(any(UserId.class)))
                .thenReturn(mock(ProfileData.class));

        // when
        MemberData result = memberSignService.getOrCreateMemberFor(existingAccount);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getUserAccountId()).isEqualTo(789L);
        verify(memberRepository).save(any(MemberData.class));
        verify(userProfileCommandService).createProfileDataForMember(existingAccount.getUserId());
        verify(playlistSetupPort).createDefaultPlaylist(existingAccount.getUserId());
        verify(applicationEventPublisher).publishEvent(any(MemberRegisteredEvent.class));
        // Critical: no recordLogin side effect on the UA.
        assertThat(existingAccount.getLastLoginAt()).isNull();
    }

    @Test
    @DisplayName("getOrCreateMemberFor — Member가 이미 존재하면 그것을 그대로 반환하고 어느 부수효과도 수행하지 않는다")
    void getOrCreateMemberForReturnsExistingMember() {
        // given
        UserAccountData existingAccount = UserAccountData.createForSocial(
                new UserId(790L), EMAIL, ProviderType.GOOGLE);
        MemberData existingMember = mock(MemberData.class);
        when(memberRepository.findByUserAccountId(790L)).thenReturn(Optional.of(existingMember));

        // when
        MemberData result = memberSignService.getOrCreateMemberFor(existingAccount);

        // then
        assertThat(result).isSameAs(existingMember);
        verify(memberRepository, never()).save(any(MemberData.class));
        verify(userProfileCommandService, never()).createProfileDataForMember(any(UserId.class));
        verify(playlistSetupPort, never()).createDefaultPlaylist(any(UserId.class));
        verify(applicationEventPublisher, never()).publishEvent(any(MemberRegisteredEvent.class));
        // No recordLogin side effect.
        assertThat(existingAccount.getLastLoginAt()).isNull();
    }

    @Test
    @DisplayName("getOrCreateMemberFor — LOCAL provider도 동일하게 처리되어 admin invite 경로가 동작한다")
    void getOrCreateMemberForHandlesLocalProvider() {
        // given — admin invite path: UA was created via createForLocalWithMandatoryChange
        UserAccountData adminAccount = UserAccountData.createForLocalWithMandatoryChange(
                new UserId(791L), "admin@x", "bcrypt-temp-hash");
        when(memberRepository.findByUserAccountId(791L)).thenReturn(Optional.empty());
        when(memberRepository.save(any(MemberData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userProfileCommandService.createProfileDataForMember(any(UserId.class)))
                .thenReturn(mock(ProfileData.class));

        // when
        MemberData result = memberSignService.getOrCreateMemberFor(adminAccount);

        // then — works identically to the social path
        assertThat(result).isNotNull();
        verify(memberRepository).save(any(MemberData.class));
        assertThat(adminAccount.getLastLoginAt()).isNull();
        // mustChangePassword stays true; no flag mutation in this method
        assertThat(adminAccount.isMustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("getMemberOrCreateWithStatus — UserAccount 미존재 시 isNewUser=true (Amplitude L4)")
    void getMemberOrCreateWithStatusReturnsTrueWhenUserAccountMissing() {
        // given
        when(userAccountRepository.findByEmailAndProviderType(EMAIL, ProviderType.GOOGLE))
                .thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccountData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(memberRepository.findByUserAccountId(any(Long.class)))
                .thenReturn(Optional.empty());
        when(memberRepository.save(any(MemberData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userProfileCommandService.createProfileDataForMember(any(UserId.class)))
                .thenReturn(mock(ProfileData.class));

        // when
        MemberSignResult result = memberSignService.getMemberOrCreateWithStatus(EMAIL, ProviderType.GOOGLE);

        // then
        assertThat(result.isNewUser()).isTrue();
        assertThat(result.member()).isNotNull();
    }

    @Test
    @DisplayName("getMemberOrCreateWithStatus — 기존 UserAccount + 기존 Member 시 isNewUser=false (Amplitude L4)")
    void getMemberOrCreateWithStatusReturnsFalseWhenBothPresent() {
        // given
        UserAccountData existingAccount = UserAccountData.createForSocial(
                new UserId(123L), EMAIL, ProviderType.GOOGLE);
        MemberData existingMember = mock(MemberData.class);

        when(userAccountRepository.findByEmailAndProviderType(EMAIL, ProviderType.GOOGLE))
                .thenReturn(Optional.of(existingAccount));
        when(memberRepository.findByUserAccountId(123L))
                .thenReturn(Optional.of(existingMember));

        // when
        MemberSignResult result = memberSignService.getMemberOrCreateWithStatus(EMAIL, ProviderType.GOOGLE);

        // then
        assertThat(result.isNewUser()).isFalse();
        assertThat(result.member()).isSameAs(existingMember);
    }

    @Test
    @DisplayName("getMemberOrCreateWithStatus — UserAccount는 있고 Member만 복구되는 경우 isNewUser=false (returning user from analytics 관점)")
    void getMemberOrCreateWithStatusReturnsFalseOnMemberRecovery() {
        // given — UA exists (returning user), but Member row was lost; recovery is NOT a sign-up
        UserAccountData existingAccount = UserAccountData.createForSocial(
                new UserId(456L), EMAIL, ProviderType.GOOGLE);

        when(userAccountRepository.findByEmailAndProviderType(EMAIL, ProviderType.GOOGLE))
                .thenReturn(Optional.of(existingAccount));
        when(memberRepository.findByUserAccountId(456L))
                .thenReturn(Optional.empty());
        when(memberRepository.save(any(MemberData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userProfileCommandService.createProfileDataForMember(any(UserId.class)))
                .thenReturn(mock(ProfileData.class));

        // when
        MemberSignResult result = memberSignService.getMemberOrCreateWithStatus(EMAIL, ProviderType.GOOGLE);

        // then — Member was newly saved, but UserAccount was not, so isNewUser=false
        assertThat(result.isNewUser()).isFalse();
        verify(memberRepository).save(any(MemberData.class));
    }

    @Test
    @DisplayName("getOAuth2RedirectUri — OAuth2 리다이렉트 URI를 반환한다")
    void getOAuth2RedirectUriSuccess() {
        // given
        String expectedUri = "https://accounts.google.com/o/oauth2/v2/auth?...";
        when(oauth2RedirectPort.getRedirectUri(GOOGLE, CALLBACK_PATH)).thenReturn(expectedUri);

        SignMemberCommand command = new SignMemberCommand(GOOGLE);

        // when
        String result = memberSignService.getOAuth2RedirectUri(command, CALLBACK_PATH);

        // then
        assertThat(result).isEqualTo(expectedUri);
        verify(oauth2RedirectPort).getRedirectUri(GOOGLE, CALLBACK_PATH);
    }
}
