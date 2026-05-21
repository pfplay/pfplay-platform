package com.pfplaybackend.api.user.application.service.initialize;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserProfileRepository;
import com.pfplaybackend.api.user.application.port.out.PlaylistSetupPort;
import com.pfplaybackend.api.user.application.service.UserActivityCommandService;
import com.pfplaybackend.api.user.application.service.UserAvatarQueryService;
import com.pfplaybackend.api.user.application.service.UserProfileCommandService;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.user.domain.value.Nickname;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemporaryUserInitializeServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock GuestRepository guestRepository;
    @Mock MemberRepository memberRepository;
    @Mock UserProfileRepository userProfileRepository;
    @Mock UserProfileCommandService userProfileCommandService;
    @Mock UserActivityCommandService userActivityCommandService;
    @Mock UserAvatarQueryService userAvatarQueryService;
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
    @DisplayName("upgradeMember — nickname unique 생성 + default avatar attach + 3회 save")
    void upgradeMemberSuccess() {
        // given
        MemberData member = mock(MemberData.class);
        AvatarBodyDto bodyResource = mock(AvatarBodyDto.class);
        AvatarBodyUri bodyUri = new AvatarBodyUri("https://cdn/body.png");
        AvatarFaceUri faceUri = new AvatarFaceUri("https://cdn/face.png");
        AvatarIconUri iconUri = new AvatarIconUri("https://cdn/icon.png");
        when(userProfileRepository.existsByNickname(any(Nickname.class))).thenReturn(false);
        when(userAvatarQueryService.getDefaultAvatarBody()).thenReturn(bodyResource);
        when(bodyResource.isCombinable()).thenReturn(true);
        when(bodyResource.getCombinePositionX()).thenReturn(60);
        when(bodyResource.getCombinePositionY()).thenReturn(41);
        when(userAvatarQueryService.getDefaultAvatarBodyUri()).thenReturn(bodyUri);
        when(userAvatarQueryService.getDefaultAvatarFaceUri()).thenReturn(faceUri);
        when(userAvatarQueryService.getDefaultAvatarIconUri()).thenReturn(iconUri);

        // when
        temporaryUserInitializeService.upgradeMember(member);

        // then — V15 unique 제약 우회: nickname-<8자 hex>로 매번 다른 값
        ArgumentCaptor<String> nickCaptor = ArgumentCaptor.forClass(String.class);
        verify(member).updateProfileBio(nickCaptor.capture(), eq("introduction"));
        assertThat(nickCaptor.getValue())
                .startsWith("nickname-")
                .hasSize("nickname-".length() + 8)
                .matches("nickname-[0-9a-f]{8}");
        verify(userProfileRepository).existsByNickname(any(Nickname.class));
        verify(member).updateWalletAddress(any());
        // avatar default attach (broadcaster NPE 방지)
        verify(member).updateAvatarBody(eq(bodyUri), eq(60), eq(41));
        // scale 은 배율 단위(1.0=원본). 과거 100.0(=100배) 은 프론트에서 face 가
        // 100배 확대돼 까맣게/가로스크롤을 유발했다. 기본은 1.0(원본).
        verify(member).updateAvatarFace(eq(faceUri), eq(FaceSourceType.INTERNAL_IMAGE),
                eq(0.0), eq(0.0), eq(1.0));
        verify(member).updateAvatarIcon(eq(iconUri));
        // profile / wallet / avatar 각각 save
        verify(memberRepository, times(3)).save(member);
    }

    @Test
    @DisplayName("upgradeMember — default body 가 combinable=false(유령) 면 SINGLE_BODY: face 비우고 body 페어 아이콘")
    void upgradeMemberSingleBodyWhenDefaultBodyNotCombinable() {
        // given: default body 가 유령(combinable=false)
        MemberData member = mock(MemberData.class);
        AvatarBodyDto bodyResource = mock(AvatarBodyDto.class);
        AvatarBodyUri bodyUri = new AvatarBodyUri("https://cdn/ghost-body.png");
        AvatarIconUri bodyPairIcon = new AvatarIconUri("https://cdn/ghost-body-pair-icon.png");
        when(userProfileRepository.existsByNickname(any(Nickname.class))).thenReturn(false);
        when(userAvatarQueryService.getDefaultAvatarBody()).thenReturn(bodyResource);
        when(bodyResource.isCombinable()).thenReturn(false);
        when(bodyResource.getCombinePositionX()).thenReturn(0);
        when(bodyResource.getCombinePositionY()).thenReturn(0);
        when(userAvatarQueryService.getDefaultAvatarBodyUri()).thenReturn(bodyUri);
        when(userAvatarQueryService.getDefaultAvatarBodyPairIconUri()).thenReturn(bodyPairIcon);

        // when
        temporaryUserInitializeService.upgradeMember(member);

        // then: SINGLE_BODY — face 빈 값(single-arg), body 페어 아이콘. face+transform(BODY_WITH_FACE) 미호출.
        verify(member).updateAvatarBody(eq(bodyUri), eq(0), eq(0));
        verify(member).updateAvatarFace(argThat((AvatarFaceUri u) -> u.getValue().isEmpty()));
        verify(member).updateAvatarIcon(eq(bodyPairIcon));
        verify(member, never()).updateAvatarFace(any(), any(), anyDouble(), anyDouble(), anyDouble());
        verify(userAvatarQueryService, never()).getDefaultAvatarFaceUri();
    }

    @Test
    @DisplayName("upgradeMember — nickname 충돌 시 재시도 후 unique 후보를 채택한다")
    void upgradeMemberRetriesOnNicknameCollision() {
        // given
        MemberData member = mock(MemberData.class);
        AvatarBodyDto bodyResource = mock(AvatarBodyDto.class);
        // 첫 시도는 충돌, 두 번째 시도는 성공
        when(userProfileRepository.existsByNickname(any(Nickname.class)))
                .thenReturn(true)
                .thenReturn(false);
        when(userAvatarQueryService.getDefaultAvatarBody()).thenReturn(bodyResource);
        when(bodyResource.isCombinable()).thenReturn(true);
        when(userAvatarQueryService.getDefaultAvatarBodyUri()).thenReturn(new AvatarBodyUri("u"));
        when(userAvatarQueryService.getDefaultAvatarFaceUri()).thenReturn(new AvatarFaceUri("u"));
        when(userAvatarQueryService.getDefaultAvatarIconUri()).thenReturn(new AvatarIconUri("u"));

        // when
        temporaryUserInitializeService.upgradeMember(member);

        // then — 두 번째 후보가 nickname으로 채택됨
        verify(userProfileRepository, times(2)).existsByNickname(any(Nickname.class));
        verify(member).updateProfileBio(any(String.class), eq("introduction"));
    }
}
