package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserProfileRepository;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSummaryDto;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.user.domain.value.Nickname;
import com.pfplaybackend.api.user.domain.value.ProfileSummary;
import com.pfplaybackend.api.user.domain.value.WalletAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileQueryServiceTest {

    @Mock UserProfileRepository userProfileRepository;
    @Mock GuestRepository guestRepository;
    @Mock MemberRepository memberRepository;
    @Mock ActivityRepository activityRepository;

    @InjectMocks UserProfileQueryService userProfileQueryService;

    private final UserId userId = new UserId(1L);

    @BeforeEach
    void setUp() {
        AuthContext authContext = mock(AuthContext.class);
        lenient().when(authContext.getUserId()).thenReturn(userId);
        lenient().when(authContext.getAuthorityTier()).thenReturn(AuthorityTier.FM);
        ThreadLocalContext.setContext(authContext);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    private ProfileData createProfileData(UserId uid, String nickname) {
        return ProfileData.builder()
                .userId(uid)
                .nickname(new Nickname(nickname))
                .avatarCompositionType(AvatarCompositionType.BODY_WITH_FACE)
                .faceSourceType(FaceSourceType.INTERNAL_IMAGE)
                .avatarBodyUri(new AvatarBodyUri("body_uri"))
                .avatarFaceUri(new AvatarFaceUri("face_uri"))
                .avatarIconUri(new AvatarIconUri("icon_uri"))
                .walletAddress(new WalletAddress(""))
                .build();
    }

    // ========== getUsersProfileSetting ==========

    @Test
    @DisplayName("getUsersProfileSetting — 다수 사용자의 프로필 설정 정보를 일괄 조회한다")
    void getUsersProfileSettingMultipleUsers() {
        // given
        UserId user2 = new UserId(2L);
        ProfileData profile1 = createProfileData(userId, "User1");
        ProfileData profile2 = createProfileData(user2, "User2");

        when(userProfileRepository.findAllByUserIdIn(List.of(userId, user2)))
                .thenReturn(List.of(profile1, profile2));

        // when
        Map<UserId, ProfileSettingDto> result = userProfileQueryService.getUsersProfileSetting(List.of(userId, user2));

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(userId).nickname()).isEqualTo("User1");
        assertThat(result.get(user2).nickname()).isEqualTo("User2");
    }

    @Test
    @DisplayName("getUsersProfileSetting — 빈 사용자 목록에 대해 빈 맵을 반환한다")
    void getUsersProfileSettingEmptyList() {
        // given
        when(userProfileRepository.findAllByUserIdIn(anyList())).thenReturn(List.of());

        // when
        Map<UserId, ProfileSettingDto> result = userProfileQueryService.getUsersProfileSetting(List.of());

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getUsersProfileSetting — 프로필 없는 userId 도 placeholder 로 채워 맵에서 누락되지 않는다 (로비 NPE 방어, #291)")
    void getUsersProfileSettingFillsMissingWithPlaceholder() {
        // given: user2 는 프로필 행이 없음 (봇 아바타 생성 실패 / 더미 / phantom 데이터)
        UserId user2 = new UserId(2L);
        ProfileData profile1 = createProfileData(userId, "User1");
        when(userProfileRepository.findAllByUserIdIn(List.of(userId, user2)))
                .thenReturn(List.of(profile1)); // user2 는 silent drop 되던 케이스

        // when
        Map<UserId, ProfileSettingDto> result = userProfileQueryService.getUsersProfileSetting(List.of(userId, user2));

        // then: 요청한 모든 userId 가 맵에 존재해야 한다 (silent drop 금지)
        assertThat(result).containsKeys(userId, user2);
        // 누락 유저는 non-null placeholder → 소비처의 .avatarIconUri()/.nickname() NPE 원천 차단
        assertThat(result.get(user2)).isNotNull();
        assertThat(result.get(user2).nickname()).isNotNull();
        assertThat(result.get(user2).avatarCompositionType()).isNotNull();
        // 정상 유저는 그대로
        assertThat(result.get(userId).nickname()).isEqualTo("User1");
    }

    // ========== getMyProfileSummary ==========

    @Test
    @DisplayName("getMyProfileSummary — Member 사용자의 프로필 요약을 반환한다")
    void getMyProfileSummaryMember() {
        // given
        MemberData member = mock(MemberData.class);
        ProfileSummary summary = new ProfileSummary(
                "MemberNick", null, "body", AvatarCompositionType.BODY_WITH_FACE,
                0, 0, 0.0, 0.0, 0.0,
                "face", "icon", "", List.of());
        when(member.getProfileSummary(anyList())).thenReturn(summary);

        when(memberRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.of(member));
        when(activityRepository.findAllByUserId(userId)).thenReturn(List.of());

        // when
        ProfileSummaryDto result = userProfileQueryService.getMyProfileSummary();

        // then
        assertThat(result).isNotNull();
        assertThat(result.nickname()).isEqualTo("MemberNick");
    }

    @Test
    @DisplayName("getMyProfileSummary — Guest 사용자의 프로필 요약을 반환한다")
    void getMyProfileSummaryGuest() {
        // given — Guest 경로를 타도록 AuthorityTier.GT 설정
        AuthContext guestContext = mock(AuthContext.class);
        when(guestContext.getUserId()).thenReturn(userId);
        when(guestContext.getAuthorityTier()).thenReturn(AuthorityTier.GT);
        ThreadLocalContext.setContext(guestContext);

        GuestData guest = mock(GuestData.class);
        ProfileData profile = createProfileData(userId, "GuestNick");
        when(guest.getProfileData()).thenReturn(profile);

        when(guestRepository.findByUserAccountId(userId.getUid())).thenReturn(Optional.of(guest));

        // when
        ProfileSummaryDto result = userProfileQueryService.getMyProfileSummary();

        // then
        assertThat(result).isNotNull();
        assertThat(result.nickname()).isEqualTo("GuestNick");
    }
}
