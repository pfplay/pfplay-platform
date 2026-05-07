package com.pfplaybackend.api.avatar.application.service;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AvatarCatalogQueryService implements AvatarCatalogQueryUseCase {

    private final AvatarBodyResourceRepository bodyRepo;
    private final AvatarFaceResourceRepository faceRepo;

    @Override
    public List<AvatarBodyDto> findPublishedBodies() {
        return bodyRepo.findAllByLifecycleStatus(LifecycleStatus.PUBLISHED).stream()
                .map(AvatarBodyDto::create)
                .toList();
    }

    @Override
    public List<AvatarFaceDto> findPublishedFaces() {
        return faceRepo.findAllByLifecycleStatus(LifecycleStatus.PUBLISHED).stream()
                .map(this::toFaceDto)
                .toList();
    }

    @Override
    public Optional<AvatarBodyDto> findBodyByUri(String resourceUri) {
        AvatarBodyResourceData data = bodyRepo.findOneAvatarResourceByResourceUri(resourceUri);
        return Optional.ofNullable(data).map(AvatarBodyDto::create);
    }

    @Override
    public Optional<AvatarFaceDto> findFaceByUri(String resourceUri) {
        AvatarFaceResourceData data = faceRepo.findOneAvatarResourceByResourceUri(resourceUri);
        return Optional.ofNullable(data).map(this::toFaceDto);
    }

    @Override
    public AvatarBodyDto findDefaultBody() {
        AvatarBodyResourceData data = bodyRepo.getDefaultSettingResource()
                .orElseThrow(() -> new IllegalStateException("기본 아바타 바디 리소스가 존재하지 않습니다."));
        return AvatarBodyDto.create(data);
    }

    @Override
    public String findBodyIconUriByName(String bodyName) {
        return bodyRepo.findByName(bodyName)
                .map(AvatarBodyResourceData::getIconUri)
                .orElse(null);
    }

    @Override
    public String findFaceIconUriByName(String faceName) {
        return faceRepo.findByName(faceName)
                .map(AvatarFaceResourceData::getIconUri)
                .orElse(null);
    }

    @Override
    public boolean isBasicFaceUri(String resourceUri) {
        return faceRepo.findByResourceUri(resourceUri).isPresent();
    }

    private AvatarFaceDto toFaceDto(AvatarFaceResourceData data) {
        return new AvatarFaceDto(
                data.getId(),
                data.getName(),
                data.getResourceUri(),
                data.getObtainableType() == ObtainmentType.BASIC
        );
    }
}
