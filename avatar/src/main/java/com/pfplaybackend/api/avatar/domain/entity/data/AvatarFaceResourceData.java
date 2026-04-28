package com.pfplaybackend.api.avatar.domain.entity.data;

import com.pfplaybackend.api.avatar.domain.enums.LifecycleStatus;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.avatar.domain.exception.AvatarException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
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

    /** DRAFT → PUBLISHED. */
    public void publish(Long updatedByAdministratorId) {
        if (this.lifecycleStatus != LifecycleStatus.DRAFT) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_LIFECYCLE_TRANSITION);
        }
        this.lifecycleStatus = LifecycleStatus.PUBLISHED;
        this.updatedAt = LocalDateTime.now();
        this.updatedBy = updatedByAdministratorId;
    }

    /** PUBLISHED → RETIRED. */
    public void retire(Long updatedByAdministratorId) {
        if (this.lifecycleStatus != LifecycleStatus.PUBLISHED) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_LIFECYCLE_TRANSITION);
        }
        this.lifecycleStatus = LifecycleStatus.RETIRED;
        this.updatedAt = LocalDateTime.now();
        this.updatedBy = updatedByAdministratorId;
    }

    /** 본 이미지 URI 교체 — DRAFT에서만 허용. */
    public void replaceResourceUri(String newResourceUri, Long updatedByAdministratorId) {
        assertMutable();
        if (this.lifecycleStatus != LifecycleStatus.DRAFT) {
            throw ExceptionCreator.create(AvatarException.AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH);
        }
        this.resourceUri = newResourceUri;
        this.updatedAt = LocalDateTime.now();
        this.updatedBy = updatedByAdministratorId;
    }

    /** 아이콘 이미지 URI 교체 — DRAFT에서만 허용. */
    public void replaceIconUri(String newIconUri, Long updatedByAdministratorId) {
        assertMutable();
        if (this.lifecycleStatus != LifecycleStatus.DRAFT) {
            throw ExceptionCreator.create(AvatarException.AVATAR_IMAGE_IMMUTABLE_AFTER_PUBLISH);
        }
        this.iconUri = newIconUri;
        this.updatedAt = LocalDateTime.now();
        this.updatedBy = updatedByAdministratorId;
    }

    private void assertMutable() {
        if (this.lifecycleStatus == LifecycleStatus.RETIRED) {
            throw ExceptionCreator.create(AvatarException.AVATAR_RESOURCE_RETIRED);
        }
    }
}
