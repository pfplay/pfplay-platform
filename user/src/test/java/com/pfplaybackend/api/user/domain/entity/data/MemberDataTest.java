package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.domain.value.Nickname;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberDataTest {

    private static final Long TEST_USER_ACCOUNT_ID = 123L;

    @Test
    @DisplayName("createForUserAccount — userAccountId 기반 생성 시 AM 권한 등급과 프로필 미업데이트 상태이다")
    void createForUserAccount_defaultsToAmTierAndUnupdatedProfile() {
        // when
        MemberData member = MemberData.createForUserAccount(TEST_USER_ACCOUNT_ID);

        // then
        assertThat(member.getUserAccountId()).isEqualTo(TEST_USER_ACCOUNT_ID);
        assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.AM);
        assertThat(member.isProfileUpdated()).isFalse();
        assertThat(member.getMemberId()).isNull(); // assigned on persist
    }

    @Test
    @DisplayName("initializeProfile — ProfileData 설정 시 isProfileUpdated는 false로 유지된다")
    void initializeProfile_setsProfileDataAndKeepsIsProfileUpdatedFalse() {
        // given
        MemberData member = MemberData.createForUserAccount(1L);
        ProfileData profile = ProfileData.builder().build();

        // when
        member.initializeProfile(profile);

        // then
        assertThat(member.getProfileData()).isSameAs(profile);
        assertThat(member.isProfileUpdated()).isFalse();
    }

    @Test
    @DisplayName("updateProfileBio — 프로필 바이오 업데이트 시 isProfileUpdated가 true가 된다")
    void updateProfileBio_marksProfileUpdated() {
        // given
        MemberData member = MemberData.createForUserAccount(1L);
        ProfileData profile = ProfileData.builder()
                .nickname(new Nickname("OldNick"))
                .build();
        member.initializeProfile(profile);

        // when
        member.updateProfileBio("NewNick", "Hello!");

        // then
        assertThat(member.isProfileUpdated()).isTrue();
    }

    @Test
    @DisplayName("updateWalletAddress — 지갑 주소 설정 시 권한이 FM으로 승격된다")
    void updateWalletAddress_promotesAuthorityTierToFm() {
        // given
        MemberData member = MemberData.createForUserAccount(1L);
        ProfileData profile = ProfileData.builder()
                .nickname(new Nickname("Nick"))
                .build();
        member.initializeProfile(profile);
        assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.AM);

        // when
        member.updateWalletAddress(new WalletAddress("0x1234567890abcdef"));

        // then
        assertThat(member.getAuthorityTier()).isEqualTo(AuthorityTier.FM);
    }
}
