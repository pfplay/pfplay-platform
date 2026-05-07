package com.pfplaybackend.api.admin.application.port.out;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.dto.AvatarIconDto;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;

import java.util.List;

public interface AdminAvatarResourcePort {
    List<AvatarBodyResourceData> findAllAvatarBodyResources();
    List<AvatarFaceResourceData> findAllAvatarFaceResources();
    AvatarBodyDto findAvatarBodyByUri(AvatarBodyUri uri);
    AvatarIconUri findAvatarIconPairWithSingleBody(AvatarBodyDto bodyDto);
    AvatarIconDto findPairAvatarIconByFaceUri(AvatarFaceUri uri);
}
