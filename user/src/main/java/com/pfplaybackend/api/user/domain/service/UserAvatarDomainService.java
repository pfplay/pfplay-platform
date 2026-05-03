package com.pfplaybackend.api.user.domain.service;

import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class UserAvatarDomainService {

    public boolean isAvailableBody(ObtainmentType obtainmentType, int obtainableScore, Map<ActivityType, ActivityData> activityMap) {
        if (obtainmentType.equals(ObtainmentType.BASIC)) return true;
        ActivityType activityType = convertToActivityType(obtainmentType);
        return activityMap.get(activityType).getScore().isAtLeast(obtainableScore);
    }

    private ActivityType convertToActivityType(ObtainmentType obtainmentType) {
        switch (obtainmentType) {
            case DJ_PNT -> {
                return ActivityType.DJ_PNT;
            }
            case REF_LINK -> {
                return ActivityType.REF_LINK;
            }
            case ROOM_ACT -> {
                return ActivityType.ROOM_ACT;
            }
            default -> throw new IllegalArgumentException("Unsupported ObtainmentType: " + obtainmentType);
        }
    }
}
