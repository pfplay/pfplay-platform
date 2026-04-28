package com.pfplaybackend.api.avatar.application.dto.command;

/**
 * Face 리소스 부분 수정 명령. Face는 메타데이터가 없으므로 사실상 이미지 교체 전용.
 * 이미지 둘 다 미제공이면 no-op으로 처리한다.
 */
public record PatchAvatarFaceCommand(
        Long resourceId,
        byte[] faceImage,
        String faceContentType,
        byte[] iconImage,
        String iconContentType,
        Long administratorId
) {}
