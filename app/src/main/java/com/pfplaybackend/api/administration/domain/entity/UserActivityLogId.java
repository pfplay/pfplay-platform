package com.pfplaybackend.api.administration.domain.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * user_activity_log composite PK — (log_id, occurred_at).
 * MySQL partitioned table은 partition key를 PK에 포함해야 함 → occurred_at 필수.
 *
 * JPA 요구: implements Serializable, no-args constructor, equals/hashCode.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserActivityLogId implements Serializable {
    private Long logId;
    private LocalDateTime occurredAt;
}
