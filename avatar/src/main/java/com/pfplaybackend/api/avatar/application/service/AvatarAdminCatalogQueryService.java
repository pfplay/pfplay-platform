package com.pfplaybackend.api.avatar.application.service;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarBodyView;
import com.pfplaybackend.api.avatar.application.dto.AdminAvatarFaceView;
import com.pfplaybackend.api.avatar.application.port.in.AvatarAdminCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 어드민 카탈로그 조회. 카탈로그 규모(< 100 row)를 고려해 메모리 필터로 충분.
 * 향후 페이징/대량 시점에 QueryDSL 도입 검토.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AvatarAdminCatalogQueryService implements AvatarAdminCatalogQueryUseCase {

    private final AvatarBodyResourceRepository bodyRepo;
    private final AvatarFaceResourceRepository faceRepo;

    @Override
    public List<AdminAvatarBodyView> listBodies(LifecycleStatus status, ObtainmentType type) {
        return bodyRepo.findAll().stream()
                .filter(b -> status == null || b.getLifecycleStatus() == status)
                .filter(b -> type == null || b.getObtainableType() == type)
                .map(AdminAvatarBodyView::from)
                .toList();
    }

    @Override
    public List<AdminAvatarFaceView> listFaces(LifecycleStatus status) {
        return faceRepo.findAll().stream()
                .filter(f -> status == null || f.getLifecycleStatus() == status)
                .map(AdminAvatarFaceView::from)
                .toList();
    }

    @Override
    public AdminAvatarBodyView getBody(Long id) {
        return bodyRepo.findById(id)
                .map(AdminAvatarBodyView::from)
                .orElseThrow(() -> ExceptionCreator.create(AvatarException.AVATAR_RESOURCE_NOT_FOUND));
    }

    @Override
    public AdminAvatarFaceView getFace(Long id) {
        return faceRepo.findById(id)
                .map(AdminAvatarFaceView::from)
                .orElseThrow(() -> ExceptionCreator.create(AvatarException.AVATAR_RESOURCE_NOT_FOUND));
    }
}
