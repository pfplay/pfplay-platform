package com.pfplaybackend.api.avatar.application.dto;

import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;

import java.time.LocalDateTime;

/**
 * 어드민 카탈로그 화면용 face 리소스 뷰. 사용자 노출용 {@link AvatarFaceDto}와 분리.
 */
public record AdminAvatarFaceView(
        Long id,
        String name,
        String resourceUri,
        String iconUri,
        ObtainmentType obtainableType,
        LifecycleStatus lifecycleStatus,
        LocalDateTime createdAt,
        Long createdBy,
        LocalDateTime updatedAt,
        Long updatedBy
) {
    public static AdminAvatarFaceView from(AvatarFaceResourceData d) {
        return new AdminAvatarFaceView(
                d.getId(),
                d.getName(),
                d.getResourceUri(),
                d.getIconUri(),
                d.getObtainableType(),
                d.getLifecycleStatus(),
                d.getCreatedAt(),
                d.getCreatedBy(),
                d.getUpdatedAt(),
                d.getUpdatedBy()
        );
    }
}
