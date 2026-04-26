package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.user.domain.value.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileDataTest {

    @Test
    @DisplayName("updateBio — Bio가 없으면 새로 생성한다")
    void updateBioCreateNewBioWhenNull() {
        // given
        ProfileData profile = ProfileData.builder()
                .userId(new UserId(1L))
                .build();

        // when
        profile.updateBio("testNick", "hello world");

        // then
        assertThat(profile.getNicknameValue()).isEqualTo("testNick");
        assertThat(profile.getIntroduction()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("updateBio — 기존 Bio가 있으면 업데이트한다")
    void updateBioUpdateExistingBio() {
        // given
        ProfileData profile = ProfileData.builder()
                .userId(new UserId(1L))
                .nickname(new Nickname("oldNick"))
                .introduction("old intro")
                .build();

        // when
        profile.updateBio("newNick", "new intro");

        // then
        assertThat(profile.getNicknameValue()).isEqualTo("newNick");
        assertThat(profile.getIntroduction()).isEqualTo("new intro");
    }

    @Test
    @DisplayName("updateAvatarBody — 아바타 바디와 좌표가 설정된다")
    void updateAvatarBodySetsBodyAndCoordinates() {
        // given
        ProfileData profile = ProfileData.builder()
                .userId(new UserId(1L))
                .avatarBodyUri(new AvatarBodyUri("old-body"))
                .avatarFaceUri(new AvatarFaceUri("face"))
                .avatarIconUri(new AvatarIconUri("icon"))
                .avatarCompositionType(AvatarCompositionType.SINGLE_BODY)
                .faceSourceType(FaceSourceType.INTERNAL_IMAGE)
                .build();

        // when
        profile.updateAvatarBody(new AvatarBodyUri("new-body"), 100, 200);

        // then
        assertThat(profile.getAvatarSetting().getAvatarBodyUri().getValue()).isEqualTo("new-body");
        assertThat(profile.getAvatarSetting().getCombinePositionX()).isEqualTo(100);
        assertThat(profile.getAvatarSetting().getCombinePositionY()).isEqualTo(200);
    }

    @Test
    @DisplayName("updateAvatarIcon — 아이콘 URI가 설정된다")
    void updateAvatarIconSetsIconUri() {
        // given
        ProfileData profile = ProfileData.builder()
                .userId(new UserId(1L))
                .avatarBodyUri(new AvatarBodyUri("body"))
                .avatarFaceUri(new AvatarFaceUri("face"))
                .avatarIconUri(new AvatarIconUri("old-icon"))
                .avatarCompositionType(AvatarCompositionType.SINGLE_BODY)
                .faceSourceType(FaceSourceType.INTERNAL_IMAGE)
                .build();

        // when
        profile.updateAvatarIcon(new AvatarIconUri("new-icon"));

        // then
        assertThat(profile.getAvatarSetting().getAvatarIconUri().getValue()).isEqualTo("new-icon");
    }

    @Test
    @DisplayName("updateWalletAddress — 지갑 주소가 설정된다")
    void updateWalletAddressSetsNewAddress() {
        // given
        ProfileData profile = ProfileData.builder()
                .userId(new UserId(1L))
                .walletAddress(new WalletAddress("old-wallet"))
                .build();

        // when
        profile.updateWalletAddress(new WalletAddress("0xABC123"));

        // then
        assertThat(profile.getWalletAddress().getValue()).isEqualTo("0xABC123");
    }

    @Test
    void updateNickname_whenBioPresent_delegatesToBioPreservingIntroduction() {
        ProfileData profile = ProfileData.builder()
                .nickname(new Nickname("old"))
                .introduction("intro")
                .build();
        profile.updateNickname("new");
        assertThat(profile.getNicknameValue()).isEqualTo("new");
        assertThat(profile.getIntroduction()).isEqualTo("intro");
    }
}
