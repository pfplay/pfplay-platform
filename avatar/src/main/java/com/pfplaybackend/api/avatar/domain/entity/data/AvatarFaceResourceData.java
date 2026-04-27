package com.pfplaybackend.api.avatar.domain.entity.data;

import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Getter
@Table(name = "AVATAR_FACE_RESOURCE")
@Entity
public class AvatarFaceResourceData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(updatable = false)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String resourceUri;

    @Column(name = "icon_uri")
    private String iconUri;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private ObtainmentType obtainableType;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private LifecycleStatus lifecycleStatus;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private Long createdBy;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private Long updatedBy;

    public AvatarFaceResourceData() {}

    @Builder
    public AvatarFaceResourceData(Long id, String name, String resourceUri, String iconUri,
                                  ObtainmentType obtainableType,
                                  LifecycleStatus lifecycleStatus,
                                  LocalDateTime createdAt, Long createdBy,
                                  LocalDateTime updatedAt, Long updatedBy) {
        this.id = id;
        this.name = name;
        this.resourceUri = resourceUri;
        this.iconUri = iconUri;
        this.obtainableType = obtainableType;
        this.lifecycleStatus = lifecycleStatus;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public static AvatarFaceResourceData draft(String name, String resourceUri, String iconUri,
                                               Long createdByAdministratorId) {
        LocalDateTime now = LocalDateTime.now();
        return AvatarFaceResourceData.builder()
                .name(name)
                .resourceUri(resourceUri)
                .iconUri(iconUri)
                .obtainableType(ObtainmentType.BASIC)
                .lifecycleStatus(LifecycleStatus.DRAFT)
                .createdAt(now)
                .createdBy(createdByAdministratorId)
                .updatedAt(now)
                .updatedBy(createdByAdministratorId)
                .build();
    }
}
