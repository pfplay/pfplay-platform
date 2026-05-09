package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarIconDto;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import com.pfplaybackend.api.user.domain.service.UserAvatarDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAvatarQueryService {

    private final ActivityRepository activityRepository;
    private final UserAvatarDomainService userAvatarDomainService;
    private final AvatarResourceQueryService avatarResourceQueryService;

    public AvatarBodyDto getDefaultAvatarBody() {
        return avatarResourceQueryService.getDefaultSettingResourceAvatarBody();
    }

    @Transactional(readOnly = true)
    public AvatarBodyUri getDefaultAvatarBodyUri() {
        AvatarBodyDto data = avatarResourceQueryService.getDefaultSettingResourceAvatarBody();
        return new AvatarBodyUri(data.getResourceUri());
    }

    public AvatarFaceUri getDefaultAvatarFaceUri() {
        return new AvatarFaceUri(avatarResourceQueryService.findAllAvatarFaces().get(0).resourceUri());
    }

    public AvatarIconUri getDefaultAvatarIconUri() {
        AvatarIconDto avatarIconDto = avatarResourceQueryService.findPairAvatarIconByFaceUri(this.getDefaultAvatarFaceUri());
        return new AvatarIconUri(avatarIconDto.resourceUri());
    }

    public List<AvatarFaceDto> findMyAvatarFaces() {
        return avatarResourceQueryService.findAllAvatarFaces();
    }

    @Transactional(readOnly = true)
    public List<AvatarBodyDto> findMyAvatarBodies() {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        List<AvatarBodyDto> avatarBodyDtoList = avatarResourceQueryService.findAllAvatarBodies();
        if (authContext.getAuthorityTier() == AuthorityTier.GT) {
            return avatarBodyDtoList;
        } else {
            UserId userId = authContext.getUserId();
            // Activity rows are no longer owned by Member. Build the map for the
            // domain service from a direct ActivityRepository query.
            Map<ActivityType, ActivityData> activityMap = new EnumMap<>(ActivityType.class);
            for (ActivityData activity : activityRepository.findAllByUserId(userId)) {
                activityMap.put(activity.getActivityType(), activity);
            }
            return avatarBodyDtoList.stream()
                    .map(avatarBodyDto -> avatarBodyDto.toBuilder()
                            .available(userAvatarDomainService.isAvailableBody(
                                    avatarBodyDto.getObtainableType(), avatarBodyDto.getObtainableScore(), activityMap))
                            .build()
                    ).toList();
        }
    }
}
