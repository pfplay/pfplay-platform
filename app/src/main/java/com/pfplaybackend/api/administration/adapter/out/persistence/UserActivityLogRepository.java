package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * user_activity_log JPA repository.
 *
 * <p>JPA `@Id`는 `log_id` 단독 (Hibernate가 `@IdClass + IDENTITY` 조합을 거부).
 * DB 레벨 composite PK `(log_id, occurred_at)`는 V10 스키마가 보장 — `log_id`가
 * AUTO_INCREMENT라 글로벌 유일하므로 JPA 식별자로 충분.
 * `UserActivityLogId` 값 객체는 향후 projection/검색 용도로 보존.
 *
 * <p>PR 12a는 save만으로 충분.
 * PR 12b A-2 `recentActivityLog` projection 메서드는 PR 12b에서 추가.
 */
public interface UserActivityLogRepository extends JpaRepository<UserActivityLogData, Long> {
}
