package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;


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
     * Increment the user's DJ-point score by {@code point}. JPA dirty-flush
     * persists the mutation at transaction end.
     */
    @Transactional
    public void updateDjPointScore(UserId userId, int point) {
        ActivityData activity = activityRepository.findByUserIdAndActivityType(userId, ActivityType.DJ_PNT)
                .orElseThrow(() -> new NoSuchElementException(
                        "DJ_PNT ActivityData not found for userId=" + userId.getUid()));
        activity.addScore(point);
    }
}
