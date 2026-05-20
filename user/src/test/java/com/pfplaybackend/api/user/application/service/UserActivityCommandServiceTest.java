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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
    @DisplayName("updateDjPointScore — atomic UPDATE 로 DJ_PNT delta 적용 (race-safe)")
    void updateDjPointScoreAtomicDelta() {
        // given
        UserId userId = new UserId(1L);
        when(activityRepository.applyScoreDelta(userId, ActivityType.DJ_PNT, 5)).thenReturn(1);

        // when & then — applyScoreDelta 단일 호출로 끝 (read-modify-write 패턴 제거)
        assertThatNoException().isThrownBy(() ->
                userActivityCommandService.updateDjPointScore(userId, 5));

        verify(activityRepository).applyScoreDelta(userId, ActivityType.DJ_PNT, 5);
    }

    @Test
    @DisplayName("updateDjPointScore — DJ_PNT 행 없으면 silent no-op (이전 NoSuchElementException → WARN 로그 + drop)")
    void updateDjPointScoreSilentNoopWhenMissing() {
        // given
        UserId userId = new UserId(1L);
        when(activityRepository.applyScoreDelta(userId, ActivityType.DJ_PNT, 5)).thenReturn(0);

        // when & then — exception 없음, 호출자 (PlaybackReactionPostProcessCommandService) 의
        // 흐름이 시드 누락 케이스에 의해 깨지지 않게 boundary 동작.
        assertThatNoException().isThrownBy(() ->
                userActivityCommandService.updateDjPointScore(userId, 5));
    }

    @Test
    @DisplayName("updateDjPointScore — 음수 delta 도 atomic UPDATE 로 전달 (DB 측 GREATEST 가 floor 가드)")
    void updateDjPointScoreNegativeDeltaPropagates() {
        // given — 청취자가 reaction 토글로 -delta 적용하는 케이스
        UserId userId = new UserId(1L);
        when(activityRepository.applyScoreDelta(userId, ActivityType.DJ_PNT, -3)).thenReturn(1);

        // when
        userActivityCommandService.updateDjPointScore(userId, -3);

        // then — 음수 floor 는 DB-side GREATEST(0, ...) 책임. 서비스는 delta 만 전달.
        verify(activityRepository).applyScoreDelta(userId, ActivityType.DJ_PNT, -3);
    }
}
