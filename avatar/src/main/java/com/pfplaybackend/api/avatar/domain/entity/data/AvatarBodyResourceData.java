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

    public void updateResource(String resourceUri, ObtainmentType obtainableType, int obtainableScore,
                               boolean isCombinable, boolean isDefaultSetting, int combinePositionX, int combinePositionY) {
        this.resourceUri = resourceUri;
        this.obtainableType = obtainableType;
        this.obtainableScore = obtainableScore;
        this.isCombinable = isCombinable;
        this.isDefaultSetting = isDefaultSetting;
        this.combinePositionX = combinePositionX;
        this.combinePositionY = combinePositionY;
    }
}
