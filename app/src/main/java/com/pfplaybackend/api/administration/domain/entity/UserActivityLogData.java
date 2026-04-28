package com.pfplaybackend.api.administration.domain.entity;

import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.administration.domain.value.JsonMetadataConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * user_activity_log row mapping (V10).
 *
 * Append-only audit timeline. listener는 `UserActivityLogListener`(Administration BC).
 *
 * <p><b>PK 매핑 노트:</b> DB 레벨 PK는 `(log_id, occurred_at)` composite (MySQL partition key 요구).
 * 그러나 Hibernate는 `@IdClass + GenerationType.IDENTITY` 조합을 거부한다
 * (`Identity generation isn't supported for composite ids`).
 * 해결: `log_id` 단독을 JPA `@Id`로 매핑.
 * `log_id`는 `BIGINT AUTO_INCREMENT`라 글로벌 유일하므로 JPA 식별자로 충분.
 * `occurred_at`은 일반 컬럼으로 매핑하되 DB-level PK 제약은 V10 스키마에 의해 그대로 유지.
 *
 * eventType은 VARCHAR(64) 컬럼이라 enum.name() 직렬화로 저장 (도메인측 enum은 listener에서 변환).
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12a-design.md §4.1
 */
@Entity
@Table(name = "user_activity_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserActivityLogData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "user_account_id", nullable = false)
    private Long userAccountId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "partyroom_id")
    private Long partyroomId;

    @Convert(converter = JsonMetadataConverter.class)
    @Column(name = "metadata", columnDefinition = "JSON")
    private JsonMetadata metadata;

    /**
     * Append-only audit row 생성. listener가 비즈니스 commit 후 호출.
     * `userAccountId`/`eventType`/`occurredAt`은 NOT NULL이라 호출자가 반드시 채워야 한다.
     * `partyroomId`/`metadata`는 nullable (룸 무관 이벤트 + metadata 없는 이벤트 허용).
     */
    public static UserActivityLogData of(Long userAccountId,
                                         UserActivityEventType eventType,
                                         Long partyroomId,
                                         JsonMetadata metadata,
                                         LocalDateTime occurredAt) {
        Objects.requireNonNull(userAccountId, "userAccountId required");
        Objects.requireNonNull(eventType, "eventType required");
        Objects.requireNonNull(occurredAt, "occurredAt required");
        UserActivityLogData d = new UserActivityLogData();
        d.userAccountId = userAccountId;
        d.eventType = eventType.name();
        d.partyroomId = partyroomId;
        d.metadata = metadata;
        d.occurredAt = occurredAt;
        return d;
    }
}
