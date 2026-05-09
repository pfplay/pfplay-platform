package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserActivityCommandServiceTest {

    @Mock ActivityRepository activityRepository;
    @InjectMocks UserActivityCommandService userActivityCommandService;

    @Test
    @DisplayName("createUserActivities — 모든 ActivityType에 대해 활동 데이터를 생성하고 저장한다")
    void createUserActivitiesCreatesAllActivityTypes() {
        // given
        UserId userId = new UserId(1L);

        // when
        userActivityCommandService.createUserActivities(userId);

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ActivityData>> captor = ArgumentCaptor.forClass(List.class);
        verify(activityRepository).saveAll(captor.capture());

        List<ActivityData> saved = captor.getValue();
        assertThat(saved).hasSize(ActivityType.values().length);
        for (ActivityType type : ActivityType.values()) {
            assertThat(saved.stream().anyMatch(a ->
                    a.getActivityType() == type && a.getUserId().equals(userId))).isTrue();
        }
    }

    @Test
    @DisplayName("updateDjPointScore — DJ_PNT 활동 데이터의 점수를 증가시킨다")
    void updateDjPointScoreIncrementsScore() {
        // given
        UserId userId = new UserId(1L);
        ActivityData djActivity = ActivityData.create(userId, ActivityType.DJ_PNT, 10);
        when(activityRepository.findByUserIdAndActivityType(userId, ActivityType.DJ_PNT))
                .thenReturn(Optional.of(djActivity));

        // when
        userActivityCommandService.updateDjPointScore(userId, 5);

        // then — JPA dirty-flush handles persistence; verify in-memory mutation
        assertThat(djActivity.getScore().getValue()).isEqualTo(15);
    }

    @Test
    @DisplayName("updateDjPointScore — DJ_PNT 데이터가 없으면 NoSuchElementException 발생")
    void updateDjPointScoreThrowsWhenMissing() {
        // given
        UserId userId = new UserId(1L);
        when(activityRepository.findByUserIdAndActivityType(userId, ActivityType.DJ_PNT))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userActivityCommandService.updateDjPointScore(userId, 5))
                .isInstanceOf(NoSuchElementException.class);
    }
}
