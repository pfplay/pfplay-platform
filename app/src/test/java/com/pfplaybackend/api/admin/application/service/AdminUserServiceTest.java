package com.pfplaybackend.api.admin.application.service;

import com.pfplaybackend.api.admin.application.port.out.AdminMemberPort;
import com.pfplaybackend.api.admin.application.port.out.AdminPlaylistPort;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.ForbiddenException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.user.domain.value.Nickname;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private AdminMemberPort adminMemberPort;

    @Mock
    private AdminProfileService adminProfileService;

    @Mock
    private AdminPlaylistPort adminPlaylistPort;

    @Mock
    private UserAccountRepository userAccountRepository;

    @InjectMocks
    private AdminUserService adminUserService;

    private MemberData createMemberFor(Long userAccountId) {
        MemberData member = MemberData.createForUserAccount(userAccountId);
        member.initializeProfile(createDummyProfile(new UserId(userAccountId)));
        return member;
    }

    private UserAccountData createLocalAccount(UserId userId) {
        return UserAccountData.createForLocal(userId, "virtual_test@pfplay.system", null);
    }

    private UserAccountData createGoogleAccount(UserId userId) {
        return UserAccountData.createForSocial(userId, "user@gmail.com", ProviderType.GOOGLE);
    }

    private ProfileData createDummyProfile(UserId userId) {
        return ProfileData.builder()
                .userId(userId)
                .nickname(new Nickname("Virtual_AABBCC"))
                .introduction("")
                .build();
    }

    @Test
    @DisplayName("createVirtualMember — 성공 시 LOCAL UserAccount + Member 가 생성되고 FM으로 업그레이드된다")
    void createVirtualMemberSuccess() {
        // given
        ProfileData dummyProfile = createDummyProfile(new UserId(1L));

        when(adminMemberPort.findMemberByEmail(anyString())).thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccountData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(adminProfileService.createProfileForVirtualMember(any(UserId.class), any(), any(), any()))
                .thenReturn(dummyProfile);
        when(adminMemberPort.saveMember(any(MemberData.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        MemberData result = adminUserService.createVirtualMember();

        // then
        assertThat(result.getAuthorityTier()).isEqualTo(AuthorityTier.FM);
        verify(userAccountRepository).save(any(UserAccountData.class));
        verify(adminPlaylistPort).createDefaultPlaylist(any(UserId.class));
        verify(adminMemberPort, times(2)).saveMember(any(MemberData.class));
    }

    @Test
    @DisplayName("createVirtualMember — 생성된 UserAccount 의 이메일은 virtual_*@pfplay.system 형식이고 LOCAL 프로바이더이며 password_hash 는 null 이다")
    void createVirtualMemberPersistsLocalAccountWithVirtualEmail() {
        // given
        ProfileData dummyProfile = createDummyProfile(new UserId(1L));

        when(adminMemberPort.findMemberByEmail(anyString())).thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccountData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(adminProfileService.createProfileForVirtualMember(any(UserId.class), any(), any(), any()))
                .thenReturn(dummyProfile);
        when(adminMemberPort.saveMember(any(MemberData.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        adminUserService.createVirtualMember();

        // then
        org.mockito.ArgumentCaptor<UserAccountData> captor =
                org.mockito.ArgumentCaptor.forClass(UserAccountData.class);
        verify(userAccountRepository).save(captor.capture());
        UserAccountData persisted = captor.getValue();
        assertThat(persisted.getProviderType()).isEqualTo(ProviderType.LOCAL);
        assertThat(persisted.getPasswordHash()).isNull();
        assertThat(persisted.getEmail())
                .startsWith("virtual_")
                .endsWith("@pfplay.system");
    }

    @Test
    @DisplayName("updateVirtualMemberAvatar — 가상 회원의 아바타 업데이트가 성공한다")
    void updateVirtualMemberAvatarSuccess() {
        // given
        UserId userId = new UserId(200L);
        MemberData virtualMember = createMemberFor(userId.getUid());
        UserAccountData localAccount = createLocalAccount(userId);
        ProfileData existingProfile = virtualMember.getProfileData();   // 교체되면 안 되는 기존 row

        AvatarBodyUri newBodyUri = new AvatarBodyUri("new_body.png");
        AvatarFaceUri newFaceUri = new AvatarFaceUri("new_face.png");

        when(adminMemberPort.findMemberByUserAccountId(userId.getUid())).thenReturn(Optional.of(virtualMember));
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(localAccount));
        when(adminMemberPort.saveMember(any(MemberData.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        MemberData result = adminUserService.updateVirtualMemberAvatar(userId, newBodyUri, newFaceUri);

        // then: 기존 profile row 를 MUTATE 해야 한다 — 교체(createProfileForVirtualMember) 금지.
        verify(adminProfileService).applyAvatarToExistingMember(virtualMember, newBodyUri, newFaceUri);
        verify(adminProfileService, never()).createProfileForVirtualMember(any(), any(), any(), any());
        // 동일 ProfileData 참조가 유지돼야(swap 아님) → 같은 user_profile id 가 UPDATE 된다.
        assertThat(result.getProfileData()).isSameAs(existingProfile);
        verify(adminMemberPort).saveMember(virtualMember);
    }

    @Test
    @DisplayName("updateVirtualMemberAvatar — 비가상(GOOGLE) 회원 아바타 업데이트 시 ForbiddenException이 발생한다")
    void updateVirtualMemberAvatarNonVirtualThrows() {
        // given
        UserId userId = new UserId(201L);
        MemberData googleMember = createMemberFor(userId.getUid());
        UserAccountData googleAccount = createGoogleAccount(userId);
        AvatarBodyUri bodyUri = new AvatarBodyUri("body.png");
        AvatarFaceUri faceUri = new AvatarFaceUri("face.png");

        when(adminMemberPort.findMemberByUserAccountId(userId.getUid())).thenReturn(Optional.of(googleMember));
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(googleAccount));

        // when & then
        assertThatThrownBy(() -> adminUserService.updateVirtualMemberAvatar(userId, bodyUri, faceUri))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("deleteVirtualMember — 가상 회원 삭제가 성공한다")
    void deleteVirtualMemberSuccess() {
        // given
        UserId userId = new UserId(300L);
        MemberData virtualMember = createMemberFor(userId.getUid());
        UserAccountData localAccount = createLocalAccount(userId);

        when(adminMemberPort.findMemberByUserAccountId(userId.getUid())).thenReturn(Optional.of(virtualMember));
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(localAccount));

        // when
        adminUserService.deleteVirtualMember(userId);

        // then
        verify(adminMemberPort).deleteMemberById(userId.getUid());
    }

    @Test
    @DisplayName("deleteVirtualMember — 비가상(GOOGLE) 회원 삭제 시 ForbiddenException이 발생한다")
    void deleteVirtualMemberNonVirtualThrows() {
        // given
        UserId userId = new UserId(301L);
        MemberData googleMember = createMemberFor(userId.getUid());
        UserAccountData googleAccount = createGoogleAccount(userId);

        when(adminMemberPort.findMemberByUserAccountId(userId.getUid())).thenReturn(Optional.of(googleMember));
        when(userAccountRepository.findById(userId)).thenReturn(Optional.of(googleAccount));

        // when & then
        assertThatThrownBy(() -> adminUserService.deleteVirtualMember(userId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("getVirtualMember — 존재하지 않는 userId로 조회 시 NotFoundException이 발생한다")
    void getVirtualMemberNotFoundThrows() {
        // given
        UserId userId = new UserId(999L);

        when(adminMemberPort.findMemberByUserAccountId(userId.getUid())).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminUserService.getVirtualMember(userId))
                .isInstanceOf(NotFoundException.class);
    }
}
