package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserProfileRepository;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.user.domain.value.Nickname;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProfileCommandService {
    private static final int GUEST_NICKNAME_MAX_ATTEMPTS = 5;

    private final UserAvatarQueryService userAvatarQueryService;
    private final UserProfileRepository userProfileRepository;

    public ProfileData createProfileDataForGuest(UserId userId) {
        return buildDefaultAvatarProfile(userId, new Nickname(generateUniqueGuestNickname()));
    }

    // 회원도 게스트/슈퍼어드민과 동일하게 디폴트 아바타를 시드한다. 닉네임만 비워두고
    // (회원 온보딩의 updateProfileBio 가 채움) 아바타 body/face/icon 은 디폴트로 채운다.
    // 이 디폴트가 없으면 모바일 가입자는 아바타 편집 진입이 막혀 영구 빈-아바타로 남는다
    // (모바일 온보딩 #393 이 전제한 '서버 자동 셋팅'을 충족). 디폴트 아바타는 is_profile_updated
    // 와 무관하므로 데스크탑 강제 온보딩 게이트(profileUpdated 일회성)에 영향 없다.
    public ProfileData createProfileDataForMember(UserId userId) {
        return buildDefaultAvatarProfile(userId, null);
    }

    public ProfileData createProfileDataForSuperAdmin(UserId userId) {
        return buildDefaultAvatarProfile(userId, new Nickname("Super Admin"));
    }

    // 디폴트 아바타 바디의 is_combinable 에 따라 BODY_WITH_FACE vs SINGLE_BODY 를 분기.
    // SINGLE_BODY 일 땐 face URI 는 비우고, icon 은 body 페어 아이콘으로 매단다.
    private ProfileData buildDefaultAvatarProfile(UserId userId, Nickname nickname) {
        AvatarBodyDto avatarBodyResource = userAvatarQueryService.getDefaultAvatarBody();
        boolean combinable = avatarBodyResource.isCombinable();
        AvatarCompositionType compositionType = combinable
                ? AvatarCompositionType.BODY_WITH_FACE
                : AvatarCompositionType.SINGLE_BODY;

        ProfileData.ProfileDataBuilder builder = ProfileData.builder()
                .userId(userId)
                .nickname(nickname)
                .avatarCompositionType(compositionType)
                .faceSourceType(FaceSourceType.INTERNAL_IMAGE)
                .avatarBodyUri(userAvatarQueryService.getDefaultAvatarBodyUri())
                .combinePositionX(avatarBodyResource.getCombinePositionX())
                .combinePositionY(avatarBodyResource.getCombinePositionY());

        if (combinable) {
            builder.avatarFaceUri(userAvatarQueryService.getDefaultAvatarFaceUri())
                    .avatarIconUri(userAvatarQueryService.getDefaultAvatarIconUri());
        } else {
            builder.avatarFaceUri(new AvatarFaceUri(""))
                    .avatarIconUri(userAvatarQueryService.getDefaultAvatarBodyPairIconUri());
        }
        return builder.build();
    }

    private String generateUniqueGuestNickname() {
        for (int attempt = 0; attempt < GUEST_NICKNAME_MAX_ATTEMPTS; attempt++) {
            String candidate = "Guest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            if (!userProfileRepository.existsByNickname(new Nickname(candidate))) {
                return candidate;
            }
        }
        return "Guest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
