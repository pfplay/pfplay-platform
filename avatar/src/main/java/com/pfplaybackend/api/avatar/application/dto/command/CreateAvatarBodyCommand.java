package com.pfplaybackend.api.avatar.application.dto.command;

import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;

/**
 * 새 아바타 body 리소스 생성 명령. {@code iconImage*}는 선택 (Spec §6.I-2).
 */
public record CreateAvatarBodyCommand(
        String name,
        byte[] bodyImage,
        String bodyContentType,
        byte[] iconImage,
        String iconContentType,
        ObtainmentType obtainableType,
        int obtainableScore,
        boolean isCombinable,
        boolean isDefaultSetting,
        int combinePositionX,
        int combinePositionY,
        Long administratorId
) {}
