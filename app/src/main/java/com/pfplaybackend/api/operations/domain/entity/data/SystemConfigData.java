package com.pfplaybackend.api.operations.domain.entity.data;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * SystemConfig aggregate root persistence entity (Operations BC).
 *
 * Key-value store. PK = config_key (String).
 *
 * Spec: docs/superpowers/specs/2026-04-19-admin-platform-schema.md §4.6
 *       docs/superpowers/specs/2026-04-19-admin-platform-design.md §3.3.4
 */
@Entity
@Table(name = "system_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DynamicUpdate
public class SystemConfigData {

    @Id
    @Column(name = "config_key", nullable = false, length = 64)
    private String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "updated_by_administrator_id")
    private Long updatedByAdministratorId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private SystemConfigData(String configKey, String configValue, String description,
                             Long updatedByAdministratorId, LocalDateTime updatedAt) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.description = description;
        this.updatedByAdministratorId = updatedByAdministratorId;
        this.updatedAt = updatedAt;
    }

    /** PR 3 has no in-app writers; updates go through SQL. Factory reserved for PR 6 admin endpoints. */
    public static SystemConfigData create(String configKey, String configValue,
                                          String description, Long updatedByAdministratorId) {
        return SystemConfigData.builder()
            .configKey(configKey)
            .configValue(configValue)
            .description(description)
            .updatedByAdministratorId(updatedByAdministratorId)
            .updatedAt(LocalDateTime.now())
            .build();
    }

    /** Reserved for PR 6. */
    public void updateValue(String newValue, Long updatedByAdministratorId) {
        this.configValue = newValue;
        this.updatedByAdministratorId = updatedByAdministratorId;
        this.updatedAt = LocalDateTime.now();
    }
}
