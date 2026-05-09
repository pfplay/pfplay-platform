package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
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
        AvatarBodyDto avatarBodyResource = userAvatarQueryService.getDefaultAvatarBody();
        return ProfileData.builder()
                .userId(userId)
                .nickname(new Nickname(generateUniqueGuestNickname()))
                .avatarCompositionType(AvatarCompositionType.BODY_WITH_FACE)
                .faceSourceType(FaceSourceType.INTERNAL_IMAGE)
                .avatarBodyUri(userAvatarQueryService.getDefaultAvatarBodyUri())
                .avatarFaceUri(userAvatarQueryService.getDefaultAvatarFaceUri())
                .avatarIconUri(userAvatarQueryService.getDefaultAvatarIconUri())
                .combinePositionX(avatarBodyResource.getCombinePositionX())
                .combinePositionY(avatarBodyResource.getCombinePositionY())
                .build();
    }

    public ProfileData createProfileDataForMember(UserId userId) {
        return ProfileData.builder()
                .userId(userId)
                .build();
    }

    public ProfileData createProfileDataForSuperAdmin(UserId userId) {
        AvatarBodyDto avatarBodyResource = userAvatarQueryService.getDefaultAvatarBody();
        return ProfileData.builder()
                .userId(userId)
                .nickname(new Nickname("Super Admin"))
                .avatarCompositionType(AvatarCompositionType.BODY_WITH_FACE)
                .faceSourceType(FaceSourceType.INTERNAL_IMAGE)
                .avatarBodyUri(userAvatarQueryService.getDefaultAvatarBodyUri())
                .avatarFaceUri(userAvatarQueryService.getDefaultAvatarFaceUri())
                .avatarIconUri(userAvatarQueryService.getDefaultAvatarIconUri())
                .combinePositionX(avatarBodyResource.getCombinePositionX())
                .combinePositionY(avatarBodyResource.getCombinePositionY())
                .build();
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
