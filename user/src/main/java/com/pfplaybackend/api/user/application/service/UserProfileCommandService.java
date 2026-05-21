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

    public ProfileData createProfileDataForMember(UserId userId) {
        return ProfileData.builder()
                .userId(userId)
                .build();
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
