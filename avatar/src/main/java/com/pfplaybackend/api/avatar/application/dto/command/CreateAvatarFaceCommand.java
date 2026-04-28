package com.pfplaybackend.api.avatar.application.dto.command;

/**
 * 새 아바타 face 리소스 생성 명령. Face는 항상 BASIC. Spec §6.I-2.
 */
public record CreateAvatarFaceCommand(
        String name,
        byte[] faceImage,
        String faceContentType,
        byte[] iconImage,
        String iconContentType,
        Long administratorId
) {}
