package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityCommandService {

    private final ActivityRepository activityRepository;

    /**
     * Persist one {@link ActivityData} row per {@link ActivityType} for the
     * given user. Replaces the pre-V4 {@code Map}-returning factory: callers
     * no longer need to feed the map back into {@code MemberData} since
     * Member no longer owns the activity collection.
     */
    @Transactional
    public void createUserActivities(UserId userId) {
        List<ActivityData> activities = new ArrayList<>();
        for (ActivityType activityType : ActivityType.values()) {
            activities.add(ActivityData.create(userId, activityType, 0));
        }
        activityRepository.saveAll(activities);
    }

    /**
     * Increment the user's DJ-point score by {@code point}. Native atomic UPDATE
     * with DB row lock — defends against the read-modify-write race that affected
     * DJ_PNT in production (same race model as E/#6 / PR #232).
     *
     * <p>The previous implementation read the entity, called {@link ActivityData#addScore},
     * and relied on JPA dirty-flush. Two concurrent reactions on the same DJ track
     * could each read a stale {@code score}, compute {@code +delta}, and one's write
     * silently overwrites the other — drifting the score downwards over time.
     *
     * <p>The atomic native UPDATE in {@link ActivityRepository#applyScoreDelta} performs
     * {@code SET score = GREATEST(0, score + :delta)} in a single statement, serialised
     * by the row lock. The {@code GREATEST} floor guard is defense-in-depth on top of
     * the {@link com.pfplaybackend.api.user.domain.value.Score} VO's own clamp.
     *
     * <p>Missing row (DJ_PNT not seeded yet for the user) is logged as WARN and the
     * delta dropped — the previous code threw {@link java.util.NoSuchElementException}
     * but the caller in {@code PlaybackReactionPostProcessCommandService} cannot
     * meaningfully recover; logging is the appropriate boundary behaviour.
     */
    @Transactional
    public void updateDjPointScore(UserId userId, int point) {
        int updated = activityRepository.applyScoreDelta(userId, ActivityType.DJ_PNT, point);
        if (updated == 0) {
            log.warn("[updateDjPointScore] no DJ_PNT row for userId={} — delta={} dropped (seed missing?)",
                    userId.getUid(), point);
        }
    }
}
