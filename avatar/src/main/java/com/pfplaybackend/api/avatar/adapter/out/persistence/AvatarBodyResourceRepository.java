package com.pfplaybackend.api.avatar.adapter.out.persistence;

import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AvatarBodyResourceRepository extends JpaRepository<AvatarBodyResourceData, Long> {
    @Query("SELECT a FROM AvatarBodyResourceData a WHERE a.isDefaultSetting = true")
    Optional<AvatarBodyResourceData> getDefaultSettingResource();

    AvatarBodyResourceData findOneAvatarResourceByResourceUri(String resourceUri);

    List<AvatarBodyResourceData> findAllByLifecycleStatus(LifecycleStatus lifecycleStatus);

    Optional<AvatarBodyResourceData> findByName(String name);

    boolean existsByName(String name);
}
