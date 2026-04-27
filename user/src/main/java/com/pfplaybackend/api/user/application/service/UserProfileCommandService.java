package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.common.domain.enums.AvatarCompositionType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.enums.FaceSourceType;
import com.pfplaybackend.api.user.domain.value.Nickname;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProfileCommandService {
    private final UserAvatarQueryService userAvatarQueryService;

    public ProfileData createProfileDataForGuest(UserId userId) {
        AvatarBodyDto avatarBodyResource = userAvatarQueryService.getDefaultAvatarBody();
        return ProfileData.builder()
                .userId(userId)
                .nickname(new Nickname(generateGuestNickname()))
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

    private String generateGuestNickname() {
        return "Guest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }
}
