package com.pfplaybackend.api.notification.adapter.out.persistence;

import com.pfplaybackend.api.notification.domain.entity.data.PushSubscriptionData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionData, Long> {
    Optional<PushSubscriptionData> findByEndpoint(String endpoint);

    /**
     * 활성(revoked_at IS NULL) 구독을 id 오름차순 keyset 페이지네이션 — fan-out 배치 로드 (web#404 B fast-follow).
     * offset 방식과 달리 insert/delete 에도 페이지 드리프트가 없고 인덱스 시킹이라 대규모에서 안정적.
     */
    List<PushSubscriptionData> findByRevokedAtIsNullAndIdGreaterThanOrderByIdAsc(Long lastId, Pageable pageable);

    /**
     * GONE(404/410) 구독 일괄 revoke — fan-out 의 HTTP 루프 밖에서 id 기반 단일 짧은 트랜잭션으로 처리.
     * (per-row dirty update + 장기 write TX 회피.)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE PushSubscriptionData s SET s.revokedAt = :now WHERE s.id IN :ids AND s.revokedAt IS NULL")
    int revokeByIds(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);
}
