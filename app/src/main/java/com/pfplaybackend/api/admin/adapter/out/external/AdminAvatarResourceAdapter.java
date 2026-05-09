package com.pfplaybackend.api.admin.adapter.out.external;

import com.pfplaybackend.api.admin.application.port.out.AdminAvatarResourcePort;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarFaceResourceRepository;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarIconDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminAvatarResourceAdapter implements AdminAvatarResourcePort {

    private final AvatarBodyResourceRepository avatarBodyResourceRepository;
    private final AvatarFaceResourceRepository avatarFaceResourceRepository;
    private final AvatarCatalogQueryUseCase avatarCatalogQueryUseCase;

    @Override
    public List<AvatarBodyResourceData> findAllAvatarBodyResources() {
        return avatarBodyResourceRepository.findAll();
    }

    @Override
    public List<AvatarFaceResourceData> findAllAvatarFaceResources() {
        return avatarFaceResourceRepository.findAll();
    }

    @Override
    public AvatarBodyDto findAvatarBodyByUri(AvatarBodyUri uri) {
        return avatarCatalogQueryUseCase.findBodyByUri(uri.getValue()).orElse(null);
    }

    @Override
    public AvatarIconUri findAvatarIconPairWithSingleBody(AvatarBodyDto bodyDto) {
        // After PR 10, body's iconUri is on the body itself.
        return new AvatarIconUri(bodyDto.getIconUri());
    }

    @Override
    public AvatarIconDto findPairAvatarIconByFaceUri(AvatarFaceUri uri) {
        AvatarFaceDto face = avatarCatalogQueryUseCase.findFaceByUri(uri.getValue()).orElseThrow();
        String iconUri = avatarCatalogQueryUseCase.findFaceIconUriByName(face.name());
        String iconName = "ava_icon_" + face.name().split("_", 2)[1];
        return new AvatarIconDto(0L, iconName, iconUri, true);
    }
}
