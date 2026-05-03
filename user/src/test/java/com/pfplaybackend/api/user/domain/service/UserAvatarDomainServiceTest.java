package com.pfplaybackend.api.user.domain.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserAvatarDomainServiceTest {

    UserAvatarDomainService userAvatarDomainService = new UserAvatarDomainService();

    private Map<ActivityType, ActivityData> createActivityMap(ActivityType type, int score) {
        Map<ActivityType, ActivityData> map = new EnumMap<>(ActivityType.class);
        map.put(type, ActivityData.create(new UserId(1L), type, score));
        return map;
    }

    @Test
    @DisplayName("isAvailableBody — BASIC 타입 아바타는 항상 사용 가능하다")
    void isAvailableBodyBasicAlwaysTrue() {
        // given
        Map<ActivityType, ActivityData> activityMap = createActivityMap(ActivityType.DJ_PNT, 0);

        // when
        boolean result = userAvatarDomainService.isAvailableBody(ObtainmentType.BASIC, 0, activityMap);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isAvailableBody — DJ_PNT 타입이고 점수가 충분하면 사용 가능하다")
    void isAvailableBodyDjPntSufficient() {
        // given
        Map<ActivityType, ActivityData> activityMap = createActivityMap(ActivityType.DJ_PNT, 100);

        // when
        boolean result = userAvatarDomainService.isAvailableBody(ObtainmentType.DJ_PNT, 50, activityMap);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isAvailableBody — DJ_PNT 타입이고 점수가 부족하면 사용 불가능하다")
    void isAvailableBodyDjPntInsufficient() {
        // given
        Map<ActivityType, ActivityData> activityMap = createActivityMap(ActivityType.DJ_PNT, 30);

        // when
        boolean result = userAvatarDomainService.isAvailableBody(ObtainmentType.DJ_PNT, 50, activityMap);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isAvailableBody — 점수가 정확히 같으면 사용 가능하다")
    void isAvailableBodyExactScoreBoundary() {
        // given
        Map<ActivityType, ActivityData> activityMap = createActivityMap(ActivityType.DJ_PNT, 50);

        // when
        boolean result = userAvatarDomainService.isAvailableBody(ObtainmentType.DJ_PNT, 50, activityMap);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isAvailableBody — ROOM_ACT 타입이고 점수가 부족하면 사용 불가능하다")
    void isAvailableBodyRoomActInsufficient() {
        // given
        Map<ActivityType, ActivityData> activityMap = createActivityMap(ActivityType.ROOM_ACT, 50);

        // when
        boolean result = userAvatarDomainService.isAvailableBody(ObtainmentType.ROOM_ACT, 100, activityMap);

        // then
        assertThat(result).isFalse();
    }
}
