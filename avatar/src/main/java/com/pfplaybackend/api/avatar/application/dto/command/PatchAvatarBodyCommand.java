package com.pfplaybackend.api.avatar.application.dto.command;

import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;

/**
 * Body 리소스 부분 수정 명령. Spec §6.I-3.
 *
 * <p>메타데이터 6필드는 항상 송신(클라이언트가 현재값 echo back).
 * 이미지({@code bodyImage}/{@code iconImage})는 선택 — 제공 시 DRAFT 상태에서만 교체 적용.
 */
public record PatchAvatarBodyCommand(
        Long resourceId,
        ObtainmentType obtainableType,
        int obtainableScore,
        boolean isCombinable,
        boolean isDefaultSetting,
        int combinePositionX,
        int combinePositionY,
        byte[] bodyImage,
        String bodyContentType,
        byte[] iconImage,
        String iconContentType,
        Long administratorId
) {}
