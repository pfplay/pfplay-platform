package com.pfplaybackend.api.user.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Delete every activity row owned by the given user. Used when a virtual
     * member is deleted via the admin API — activity rows are no longer
     * cascaded by the Member aggregate (Task 5/11), so they must be cleaned
     * up explicitly by the caller within the same transaction.
     */
    void deleteAllByUserId(UserId userId);

    /**
     * Atomically apply a {@code delta} to {@code score} for the given (user, activity_type)
     * row. Native UPDATE serialised by the DB row lock — defends against the
     * read-modify-write race that affected DJ_PNT in production (the same race
     * model as E/#6 / PR #232's {@code PlaybackAggregationRepository.applyAggregationDelta}).
     *
     * <p>{@code GREATEST(0, score + :delta)} floor guard (defense-in-depth):
     * the {@link com.pfplaybackend.api.user.domain.value.Score} VO already clamps to ≥0
     * on construction, but that runs after a stale read — under heavy contention an
     * interleaving could still produce a transient negative intermediate. The DB-side
     * {@code GREATEST} ensures the column never observes a negative value across any
     * interleaving.
     *
     *  - returns 1: row found and updated
     *  - returns 0: no row for (user, activityType) — caller decides (warn / create / throw)
     *
     * <p>{@code @Modifying(clearAutomatically = true)} invalidates the 1st-level cache
     * so subsequent reads in the same transaction see the persisted value.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE user_activity " +
           "SET score = GREATEST(0, CAST(score AS SIGNED) + :delta) " +
           "WHERE user_id = :#{#userId.uid} AND activity_type = :#{#activityType.name()}",
           nativeQuery = true)
    int applyScoreDelta(@Param("userId") UserId userId,
                        @Param("activityType") ActivityType activityType,
                        @Param("delta") int delta);
}
