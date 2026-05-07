package com.pfplaybackend.api.avatar.application.port.in;

import com.pfplaybackend.api.avatar.application.dto.AdminAvatarBodyView;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarFaceView;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;

import java.util.List;

/**
 * 어드민 카탈로그 조회 — 사용자 노출용 {@code AvatarCatalogQueryUseCase}와 분리.
 * Spec §6.I-1.
 */
public interface AvatarAdminCatalogQueryUseCase {

    /** {@code status}/{@code type}는 nullable — null이면 해당 조건 무시. */
    List<AdminAvatarBodyView> listBodies(LifecycleStatus status, ObtainmentType type);

    /** {@code status}는 nullable — null이면 모든 상태 반환. */
    List<AdminAvatarFaceView> listFaces(LifecycleStatus status);

    AdminAvatarBodyView getBody(Long id);

    AdminAvatarFaceView getFace(Long id);
}
