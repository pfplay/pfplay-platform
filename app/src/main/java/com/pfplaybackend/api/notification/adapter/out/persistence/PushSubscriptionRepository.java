package com.pfplaybackend.api.notification.adapter.out.persistence;

import com.pfplaybackend.api.notification.domain.entity.data.PushSubscriptionData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionData, Long> {
    Optional<PushSubscriptionData> findByEndpoint(String endpoint);

    @Query("SELECT s FROM PushSubscriptionData s WHERE s.revokedAt IS NULL")
    List<PushSubscriptionData> findAllActive();
}
