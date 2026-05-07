package com.pfplaybackend.api.user.domain.enums;

import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;

public enum ActivityType {
    DJ_PNT,
    REF_LINK,
    ROOM_ACT;

    public static ActivityType of(ObtainmentType obtainmentType) {
        if (obtainmentType == ObtainmentType.DJ_PNT) return DJ_PNT;
        if (obtainmentType == ObtainmentType.REF_LINK) return REF_LINK;
        if (obtainmentType == ObtainmentType.ROOM_ACT) return ROOM_ACT;
        return null;
    }
}