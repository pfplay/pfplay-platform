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
@Table(name = "AVATAR_BODY_RESOURCE")
@Entity
public class AvatarBodyResourceData {
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
    private ObtainmentType obtainableType;

    @Column(nullable = false)
    private int obtainableScore;

    @Column(nullable = false)
    private boolean isCombinable;

    @Column(nullable = false)
    private boolean isDefaultSetting;

    private int combinePositionX;
    private int combinePositionY;

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

    public AvatarBodyResourceData() {
    }

    @Builder
    public AvatarBodyResourceData(Long id, String name, String resourceUri, String iconUri,
                                  ObtainmentType obtainableType, int obtainableScore,
                                  boolean isCombinable, boolean isDefaultSetting,
                                  int combinePositionX, int combinePositionY,
                                  LifecycleStatus lifecycleStatus,
                                  LocalDateTime createdAt, Long createdBy,
                                  LocalDateTime updatedAt, Long updatedBy) {
        this.id = id;
        this.name = name;
        this.resourceUri = resourceUri;
        this.iconUri = iconUri;
        this.obtainableType = obtainableType;
        this.obtainableScore = obtainableScore;
        this.isCombinable = isCombinable;
        this.isDefaultSetting = isDefaultSetting;
        this.combinePositionX = combinePositionX;
        this.combinePositionY = combinePositionY;
        this.lifecycleStatus = lifecycleStatus;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public static AvatarBodyResourceData draft(String name, String resourceUri, String iconUri,
                                               ObtainmentType obtainableType, int obtainableScore,
                                               boolean isCombinable, boolean isDefaultSetting,
                                               int combinePositionX, int combinePositionY,
                                               Long createdByAdministratorId) {
        LocalDateTime now = LocalDateTime.now();
        return AvatarBodyResourceData.builder()
                .name(name)
                .resourceUri(resourceUri)
                .iconUri(iconUri)
                .obtainableType(obtainableType)
                .obtainableScore(obtainableScore)
                .isCombinable(isCombinable)
                .isDefaultSetting(isDefaultSetting)
                .combinePositionX(combinePositionX)
                .combinePositionY(combinePositionY)
                .lifecycleStatus(LifecycleStatus.DRAFT)
                .createdAt(now)
                .createdBy(createdByAdministratorId)
                .updatedAt(now)
                .updatedBy(createdByAdministratorId)
                .build();
    }

    /** DRAFT → PUBLISHED 전이. 그 외 상태에서 호출하면 예외. */
    public void publish(Long updatedByAdministratorId) {
        if (this.lifecycleStatus != LifecycleStatus.DRAFT) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_LIFECYCLE_TRANSITION);
        }
        this.lifecycleStatus = LifecycleStatus.PUBLISHED;
        this.updatedAt = LocalDateTime.now();
        this.updatedBy = updatedByAdministratorId;
    }

    /** PUBLISHED → RETIRED 전이. 그 외 상태에서 호출하면 예외. */
    public void retire(Long updatedByAdministratorId) {
        if (this.lifecycleStatus != LifecycleStatus.PUBLISHED) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_LIFECYCLE_TRANSITION);
        }
        this.lifecycleStatus = LifecycleStatus.RETIRED;
        this.updatedAt = LocalDateTime.now();
        this.updatedBy = updatedByAdministratorId;
    }

    /**
     * 메타데이터(획득 조건, 결합 좌표, 기본 설정 플래그) 갱신.
     * Spec §6.I-3 — DRAFT/PUBLISHED 모두 허용. RETIRED는 거부. URI/이미지는 별 메서드에서.
     */
    public void updateMetadata(ObtainmentType obtainableType, int obtainableScore,
                               boolean isCombinable, boolean isDefaultSetting,
                               int combinePositionX, int combinePositionY,
                               Long updatedByAdministratorId) {
        assertMutable();
        // BASIC ⇔ score=0 — 운영 합의 (§6.I-2)
        if (obtainableType == ObtainmentType.BASIC && obtainableScore != 0) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_DEFAULT_SETTING);
        }
        // isDefaultSetting=true ⇒ BASIC + PUBLISHED
        if (isDefaultSetting && (obtainableType != ObtainmentType.BASIC
                || this.lifecycleStatus != LifecycleStatus.PUBLISHED)) {
            throw ExceptionCreator.create(AvatarException.AVATAR_INVALID_DEFAULT_SETTING);
        }
        this.obtainableType = obtainableType;
        this.obtainableScore = obtainableScore;
        this.isCombinable = isCombinable;
        this.isDefaultSetting = isDefaultSetting;
        this.combinePositionX = combinePositionX;
        this.combinePositionY = combinePositionY;
        this.updatedAt = LocalDateTime.now();
        this.updatedBy = updatedByAdministratorId;
    }

    /** 본 이미지 URI 교체 — DRAFT에서만 허용 (Spec §6.I-3 / §6.I-4 근거). */
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
