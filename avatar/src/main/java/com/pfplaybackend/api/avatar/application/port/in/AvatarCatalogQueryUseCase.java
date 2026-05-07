package com.pfplaybackend.api.avatar.application.port.in;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarFaceDto;

import java.util.List;
import java.util.Optional;

public interface AvatarCatalogQueryUseCase {
    List<AvatarBodyDto> findPublishedBodies();

    List<AvatarFaceDto> findPublishedFaces();

    Optional<AvatarBodyDto> findBodyByUri(String resourceUri);

    Optional<AvatarFaceDto> findFaceByUri(String resourceUri);

    AvatarBodyDto findDefaultBody();

    String findBodyIconUriByName(String bodyName);

    String findFaceIconUriByName(String faceName);

    boolean isBasicFaceUri(String resourceUri);
}
