package com.pfplaybackend.api.avatar.application.dto;

import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;

import java.time.LocalDateTime;

/**
 * 어드민 카탈로그 화면용 body 리소스 뷰. 사용자 노출용 {@link AvatarBodyDto}와 분리 —
 * 감사 컬럼/lifecycleStatus 등 어드민 전용 필드 포함.
 */
public record AdminAvatarBodyView(
        Long id,
        String name,
        String resourceUri,
        String iconUri,
        ObtainmentType obtainableType,
        int obtainableScore,
        boolean isCombinable,
        boolean isDefaultSetting,
        int combinePositionX,
        int combinePositionY,
        LifecycleStatus lifecycleStatus,
        LocalDateTime createdAt,
        Long createdBy,
        LocalDateTime updatedAt,
        Long updatedBy
) {
    public static AdminAvatarBodyView from(AvatarBodyResourceData d) {
        return new AdminAvatarBodyView(
                d.getId(),
                d.getName(),
                d.getResourceUri(),
                d.getIconUri(),
                d.getObtainableType(),
                d.getObtainableScore(),
                d.isCombinable(),
                d.isDefaultSetting(),
                d.getCombinePositionX(),
                d.getCombinePositionY(),
                d.getLifecycleStatus(),
                d.getCreatedAt(),
                d.getCreatedBy(),
                d.getUpdatedAt(),
                d.getUpdatedBy()
        );
    }
}
