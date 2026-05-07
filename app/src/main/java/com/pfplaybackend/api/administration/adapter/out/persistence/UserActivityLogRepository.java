package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * user_activity_log JPA repository.
 *
 * <p>JPA `@Id`는 `log_id` 단독 (Hibernate가 `@IdClass + IDENTITY` 조합을 거부).
 * DB 레벨 composite PK `(log_id, occurred_at)`는 V10 스키마가 보장 — `log_id`가
 * AUTO_INCREMENT라 글로벌 유일하므로 JPA 식별자로 충분.
 *
 * <p>PR 12b1 A-2 `recentActivityLog` projection은 단순 derived query
 * `findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc`로 cover —
 * composite key 식별자 불필요. log_id DESC tie-breaker로 같은 occurred_at row의 결정적 순서 보장.
 *
 * Spec: docs/superpowers/specs/2026-04-28-admin-platform-pr12b1-design.md §5.1, §11 #9
 */
public interface UserActivityLogRepository extends JpaRepository<UserActivityLogData, Long> {

    /**
     * Member detail의 recentActivityLog 응답용 — 특정 user의 최근 30건.
     * idx_ual_user_time DESC + LIMIT 30 cover. partition pruning은 안 됨(WHERE on user_account_id).
     * tie-breaker: log_id DESC — 같은 occurred_at row의 결정적 순서 보장 (spec §11 #9).
     */
    List<UserActivityLogData> findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(Long userAccountId);
}
