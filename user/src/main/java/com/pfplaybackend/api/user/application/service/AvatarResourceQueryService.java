package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarIconDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AvatarResourceQueryService {

    private final AvatarCatalogQueryUseCase avatarCatalogQueryUseCase;

    public AvatarBodyDto getDefaultSettingResourceAvatarBody() {
        return avatarCatalogQueryUseCase.findDefaultBody();
    }

    public List<AvatarFaceDto> findAllAvatarFaces() {
        return avatarCatalogQueryUseCase.findPublishedFaces();
    }

    public List<AvatarBodyDto> findAllAvatarBodies() {
        return avatarCatalogQueryUseCase.findPublishedBodies();
    }

    public AvatarBodyDto findAvatarBodyByUri(AvatarBodyUri uri) {
        return avatarCatalogQueryUseCase.findBodyByUri(uri.getValue()).orElse(null);
    }

    public AvatarIconDto findPairAvatarIconByFaceUri(AvatarFaceUri uri) {
        AvatarFaceDto face = avatarCatalogQueryUseCase.findFaceByUri(uri.getValue())
                .orElseThrow();
        String iconUri = avatarCatalogQueryUseCase.findFaceIconUriByName(face.name());
        String iconName = "ava_icon_" + face.name().split("_", 2)[1];
        return new AvatarIconDto(0L, iconName, iconUri, true);
    }

    public AvatarIconDto findPairAvatarIconByBodyUri(AvatarBodyUri uri) {
        AvatarBodyDto body = avatarCatalogQueryUseCase.findBodyByUri(uri.getValue())
                .orElseThrow();
        String iconUri = avatarCatalogQueryUseCase.findBodyIconUriByName(body.getName());
        String iconName = "ava_icon_" + body.getName().split("_", 2)[1];
        return new AvatarIconDto(0L, iconName, iconUri, true);
    }

    public boolean isBasicFaceUri(AvatarFaceUri avatarFaceUri) {
        return avatarCatalogQueryUseCase.isBasicFaceUri(avatarFaceUri.getValue());
    }
}
