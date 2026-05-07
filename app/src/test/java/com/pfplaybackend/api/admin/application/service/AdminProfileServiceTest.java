package com.pfplaybackend.api.admin.application.service;

import com.pfplaybackend.api.admin.application.port.out.AdminAvatarResourcePort;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarIconDto;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProfileServiceTest {

    @Mock
    private AdminAvatarResourcePort adminAvatarResourcePort;

    @InjectMocks
    private AdminProfileService adminProfileService;

    private static final String DEFAULT_BODY_URI = "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    private AvatarBodyDto createDefaultBodyDto() {
        return AvatarBodyDto.builder()
                .id(1L)
                .name("basic_001")
                .resourceUri(DEFAULT_BODY_URI)
                .combinePositionX(10)
                .combinePositionY(20)
                .build();
    }

    @Test
    @DisplayName("createProfileForVirtualMember — 기본 파라미터로 호출 시 Virtual_ 접두사 닉네임이 생성된다")
    void createProfileForVirtualMemberDefaultParams() {
        // given
        UserId userId = new UserId(100L);
        AvatarBodyDto bodyDto = createDefaultBodyDto();
        AvatarIconUri pairedIcon = new AvatarIconUri("icon_basic_001.png");

        when(adminAvatarResourcePort.findAvatarBodyByUri(any(AvatarBodyUri.class))).thenReturn(bodyDto);
        when(adminAvatarResourcePort.findAvatarIconPairWithSingleBody(bodyDto)).thenReturn(pairedIcon);

        // when
        ProfileData profile = adminProfileService.createProfileForVirtualMember(userId);

        // then
        assertThat(profile.getNicknameValue()).startsWith("Virtual_");
        assertThat(profile.getNicknameValue()).hasSize(14); // "Virtual_" (8) + 6 hex chars
        assertThat(profile.getAvatarSetting().getAvatarCompositionType()).isEqualTo(AvatarCompositionType.SINGLE_BODY);
    }

    @Test
    @DisplayName("createProfileForVirtualMember — 커스텀 닉네임이 제공되면 그대로 사용한다")
    void createProfileForVirtualMemberCustomNickname() {
        // given
        UserId userId = new UserId(101L);
        String customNickname = "TestUser";
        AvatarBodyDto bodyDto = createDefaultBodyDto();
        AvatarIconUri pairedIcon = new AvatarIconUri("icon_basic_001.png");

        when(adminAvatarResourcePort.findAvatarBodyByUri(any(AvatarBodyUri.class))).thenReturn(bodyDto);
        when(adminAvatarResourcePort.findAvatarIconPairWithSingleBody(bodyDto)).thenReturn(pairedIcon);

        // when
        ProfileData profile = adminProfileService.createProfileForVirtualMember(
                userId, customNickname, null, null);

        // then
        assertThat(profile.getNicknameValue()).isEqualTo("TestUser");
    }

    @Test
    @DisplayName("createProfileForVirtualMember — NFT face URI 패턴 감지 시 BODY_WITH_FACE + NFT_URI로 설정된다")
    void createProfileForVirtualMemberNftFaceUri() {
        // given
        UserId userId = new UserId(102L);
        AvatarBodyUri bodyUri = new AvatarBodyUri(DEFAULT_BODY_URI);
        AvatarFaceUri nftFaceUri = new AvatarFaceUri("https://example.com/ava_nft_tmp/face_001.png");
        AvatarBodyDto bodyDto = createDefaultBodyDto();

        when(adminAvatarResourcePort.findAvatarBodyByUri(bodyUri)).thenReturn(bodyDto);

        // when
        ProfileData profile = adminProfileService.createProfileForVirtualMember(
                userId, "NftUser", bodyUri, nftFaceUri);

        // then
        assertThat(profile.getAvatarSetting().getAvatarCompositionType()).isEqualTo(AvatarCompositionType.BODY_WITH_FACE);
        assertThat(profile.getAvatarSetting().getFaceSourceType()).isEqualTo(FaceSourceType.NFT_URI);
        // NFT face URI becomes the icon URI
        assertThat(profile.getAvatarSetting().getAvatarIconUri().getValue())
                .isEqualTo(nftFaceUri.getValue());
    }

    @Test
    @DisplayName("createProfileForVirtualMember — 내부 face URI 패턴 감지 시 BODY_WITH_FACE + INTERNAL_IMAGE로 설정된다")
    void createProfileForVirtualMemberInternalFaceUri() {
        // given
        UserId userId = new UserId(103L);
        AvatarBodyUri bodyUri = new AvatarBodyUri(DEFAULT_BODY_URI);
        AvatarFaceUri internalFaceUri = new AvatarFaceUri("https://storage.example.com/face_internal_001.png");
        AvatarBodyDto bodyDto = createDefaultBodyDto();
        AvatarIconDto pairedIconDto = new AvatarIconDto(1L, "icon_face_001", "icon_face_001.png", true);

        when(adminAvatarResourcePort.findAvatarBodyByUri(bodyUri)).thenReturn(bodyDto);
        when(adminAvatarResourcePort.findPairAvatarIconByFaceUri(internalFaceUri)).thenReturn(pairedIconDto);

        // when
        ProfileData profile = adminProfileService.createProfileForVirtualMember(
                userId, "InternalFaceUser", bodyUri, internalFaceUri);

        // then
        assertThat(profile.getAvatarSetting().getAvatarCompositionType()).isEqualTo(AvatarCompositionType.BODY_WITH_FACE);
        assertThat(profile.getAvatarSetting().getFaceSourceType()).isEqualTo(FaceSourceType.INTERNAL_IMAGE);
        assertThat(profile.getAvatarSetting().getAvatarIconUri().getValue())
                .isEqualTo("icon_face_001.png");
    }

    @Test
    @DisplayName("createProfileForVirtualMember — 기본값으로 introduction 빈문자열, scale 1.0, offset 0이 설정된다")
    void createProfileForVirtualMemberDefaultValues() {
        // given
        UserId userId = new UserId(104L);
        AvatarBodyDto bodyDto = createDefaultBodyDto();
        AvatarIconUri pairedIcon = new AvatarIconUri("icon_basic_001.png");

        when(adminAvatarResourcePort.findAvatarBodyByUri(any(AvatarBodyUri.class))).thenReturn(bodyDto);
        when(adminAvatarResourcePort.findAvatarIconPairWithSingleBody(bodyDto)).thenReturn(pairedIcon);

        // when
        ProfileData profile = adminProfileService.createProfileForVirtualMember(userId);

        // then
        assertThat(profile.getIntroduction()).isEmpty();
        assertThat(profile.getAvatarSetting().getScale()).isEqualTo(1.0);
        assertThat(profile.getAvatarSetting().getOffsetX()).isEqualTo(0.0);
        assertThat(profile.getAvatarSetting().getOffsetY()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("createProfileForVirtualMember — SINGLE_BODY 타입은 body 쌍 아이콘을 사용한다")
    void createProfileForVirtualMemberSingleBodyUsesBodyPairedIcon() {
        // given
        UserId userId = new UserId(105L);
        AvatarBodyDto bodyDto = createDefaultBodyDto();
        AvatarIconUri pairedIcon = new AvatarIconUri("body_paired_icon.png");

        when(adminAvatarResourcePort.findAvatarBodyByUri(any(AvatarBodyUri.class))).thenReturn(bodyDto);
        when(adminAvatarResourcePort.findAvatarIconPairWithSingleBody(bodyDto)).thenReturn(pairedIcon);

        // when
        ProfileData profile = adminProfileService.createProfileForVirtualMember(userId);

        // then
        assertThat(profile.getAvatarSetting().getAvatarIconUri()).isEqualTo(pairedIcon);
        assertThat(profile.getAvatarSetting().getCombinePositionX()).isEqualTo(bodyDto.getCombinePositionX());
        assertThat(profile.getAvatarSetting().getCombinePositionY()).isEqualTo(bodyDto.getCombinePositionY());
    }
}
