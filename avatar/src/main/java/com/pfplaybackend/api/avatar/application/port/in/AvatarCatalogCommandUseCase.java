package com.pfplaybackend.api.avatar.application.port.in;

import com.pfplaybackend.api.avatar.application.dto.AdminAvatarBodyView;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarFaceView;
import com.pfplaybackend.api.avatar.application.dto.command.CreateAvatarBodyCommand;
import com.pfplaybackend.api.avatar.application.dto.command.CreateAvatarFaceCommand;
import com.pfplaybackend.api.avatar.application.dto.command.PatchAvatarBodyCommand;
import com.pfplaybackend.api.avatar.application.dto.command.PatchAvatarFaceCommand;

/**
 * 어드민 카탈로그 관리 — body/face 리소스 CRUD + 라이프사이클 전이. Spec §6.I-2~I-5.
 */
public interface AvatarCatalogCommandUseCase {

    AdminAvatarBodyView createBody(CreateAvatarBodyCommand command);

    AdminAvatarFaceView createFace(CreateAvatarFaceCommand command);

    AdminAvatarBodyView patchBody(PatchAvatarBodyCommand command);

    AdminAvatarFaceView patchFace(PatchAvatarFaceCommand command);

    void replaceBodyIcon(Long resourceId, byte[] iconBytes, String contentType, Long administratorId);

    void replaceFaceIcon(Long resourceId, byte[] iconBytes, String contentType, Long administratorId);

    void publishBody(Long resourceId, Long administratorId);

    void publishFace(Long resourceId, Long administratorId);

    void retireBody(Long resourceId, String reason, Long administratorId);

    void retireFace(Long resourceId, String reason, Long administratorId);
}
