package com.pfplaybackend.api.avatar.adapter.out.persistence;

import com.pfplaybackend.api.avatar.domain.entity.data.AvatarFaceResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AvatarFaceResourceRepository extends JpaRepository<AvatarFaceResourceData, Long> {
    Optional<AvatarFaceResourceData> findByResourceUri(String resourceUri);

    AvatarFaceResourceData findOneAvatarResourceByResourceUri(String resourceUri);

    List<AvatarFaceResourceData> findAllByLifecycleStatus(LifecycleStatus lifecycleStatus);

    Optional<AvatarFaceResourceData> findByName(String name);

    boolean existsByName(String name);
}
