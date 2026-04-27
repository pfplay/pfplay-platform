package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.enums.AdminActionTargetType;
import com.pfplaybackend.api.administration.domain.enums.PartyroomAdminActionType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.administration.domain.value.JsonMetadataConverter;
import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 어드민의 시스템 액션 감사 로그. Append-only — setter 없음.
 * V7 마이그레이션으로 도입. spec §3 / §6.3 참조.
 */
@AggregateRoot
@Entity
@Table(name = "PARTYROOM_ADMIN_ACTION")
@Getter
@DynamicInsert
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartyroomAdminActionData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "action_id")
    private Long id;

    @Column(name = "administrator_id", nullable = false)
    private Long administratorId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "action_type", nullable = false, length = 32)
    private PartyroomAdminActionType actionType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 16)
    private AdminActionTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "partyroom_id")
    private Long partyroomId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Convert(converter = JsonMetadataConverter.class)
    @Column(name = "metadata", columnDefinition = "JSON")
    private JsonMetadata metadata;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Builder(access = AccessLevel.PRIVATE)
    private PartyroomAdminActionData(Long administratorId, PartyroomAdminActionType actionType,
                                     AdminActionTargetType targetType, Long targetId, Long partyroomId,
                                     String reason, JsonMetadata metadata, LocalDateTime occurredAt) {
        this.administratorId = administratorId;
        this.actionType = actionType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.partyroomId = partyroomId;
        this.reason = reason;
        this.metadata = metadata;
        this.occurredAt = occurredAt;
    }

    /** 모든 필드 명시적 입력. 호출자(listener)는 이벤트 페이로드에서 값 추출 후 호출. */
    public static PartyroomAdminActionData of(Long administratorId,
                                              PartyroomAdminActionType actionType,
                                              AdminActionTargetType targetType,
                                              Long targetId,
                                              Long partyroomId,
                                              String reason,
                                              JsonMetadata metadata,
                                              LocalDateTime occurredAt) {
        return PartyroomAdminActionData.builder()
                .administratorId(administratorId)
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .partyroomId(partyroomId)
                .reason(reason)
                .metadata(metadata == null ? JsonMetadata.empty() : metadata)
                .occurredAt(occurredAt)
                .build();
    }
}
