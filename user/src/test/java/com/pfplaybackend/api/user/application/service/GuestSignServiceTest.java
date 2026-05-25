package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuestSignServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock GuestRepository guestRepository;
    @Mock UserProfileCommandService userProfileCommandService;
    @InjectMocks GuestSignService guestSignService;

    @Test
    @DisplayName("getGuestOrCreate — 게스트를 생성하고 프로필을 초기화한 뒤 저장한다")
    void getGuestOrCreateSuccess() {
        // given
        ProfileData profile = mock(ProfileData.class);
        when(userProfileCommandService.createProfileDataForGuest(any())).thenReturn(profile);
        when(guestRepository.save(any(GuestData.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        GuestData result = guestSignService.getGuestOrCreate();

        // then
        assertThat(result).isNotNull();
        assertThat(result.getUserAccountId()).isNotNull();
        verify(userAccountRepository).save(any(UserAccountData.class));
        verify(userProfileCommandService).createProfileDataForGuest(any());
        verify(guestRepository).save(any(GuestData.class));
    }

    @Test
    @DisplayName("getGuestOrCreate — 생성된 게스트에 프로필이 할당된다")
    void getGuestOrCreateProfileInitialized() {
        // given
        ProfileData profile = mock(ProfileData.class);
        when(userProfileCommandService.createProfileDataForGuest(any())).thenReturn(profile);
        when(guestRepository.save(any(GuestData.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        GuestData result = guestSignService.getGuestOrCreate();

        // then
        assertThat(result.getProfileData()).isEqualTo(profile);
        assertThat(result.isProfileUpdated()).isTrue();
    }

    @Test
    @DisplayName("getGuestOrCreate — provider_type 을 GUEST 로 저장한다 (게스트는 OAuth 미사용)")
    void getGuestOrCreate_assigns_guest_provider_type() {
        ProfileData profile = mock(ProfileData.class);
        when(userProfileCommandService.createProfileDataForGuest(any())).thenReturn(profile);
        when(guestRepository.save(any(GuestData.class))).thenAnswer(invocation -> invocation.getArgument(0));

        guestSignService.getGuestOrCreate();

        ArgumentCaptor<UserAccountData> captor = ArgumentCaptor.forClass(UserAccountData.class);
        verify(userAccountRepository).save(captor.capture());
        assertThat(captor.getValue().getProviderType()).isEqualTo(ProviderType.GUEST);
    }
}
