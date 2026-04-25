package com.pfplaybackend.api.user.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ActivityData} rows.
 *
 * <p>Activity rows are no longer owned by {@code MemberData} as a JPA association
 * (Task 5 dropped that mapping). Application services query/persist ActivityData
 * directly through this repository.
 *
 * <p>Spring Data derives both finder methods from the embedded {@code userId}
 * field by value-comparing the {@code UserId.uid} column. The legacy {@code UserId}
 * VO equals {@code member.userAccountId} by construction (see Task 8 commit).
 */
public interface ActivityRepository extends JpaRepository<ActivityData, Long> {
    Optional<ActivityData> findByUserIdAndActivityType(UserId userId, ActivityType activityType);

    List<ActivityData> findAllByUserId(UserId userId);
}
