package com.pfplaybackend.api.operations.adapter.out.persistence;

import com.pfplaybackend.api.operations.domain.entity.data.SystemConfigData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SystemConfigRepository extends JpaRepository<SystemConfigData, String> {
    Optional<SystemConfigData> findByConfigKey(String configKey);
}
