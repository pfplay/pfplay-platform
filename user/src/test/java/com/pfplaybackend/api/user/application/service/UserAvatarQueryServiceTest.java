package com.pfplaybackend.api.user.application.service;

import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.adapter.out.persistence.ActivityRepository;
import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.user.domain.entity.data.ActivityData;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.user.domain.enums.ActivityType;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.user.domain.service.UserAvatarDomainService;
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAvatarQueryServiceTest {

    private static final String BODY1 = "body1";

    @Mock ActivityRepository activityRepository;
    @Mock UserAvatarDomainService userAvatarDomainService;
    @Mock AvatarResourceQueryService avatarResourceQueryService;
    @InjectMocks UserAvatarQueryService userAvatarQueryService;

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("getDefaultAvatarBodyUri — 기본 바디 URI를 반환한다")
    void getDefaultAvatarBodyUriReturnsDefaultUri() {
        // given
        AvatarBodyDto defaultBody = AvatarBodyDto.builder()
                .id(1L).name("default").resourceUri("default-body-uri")
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .combinable(true).defaultSetting(true)
                .combinePositionX(50).combinePositionY(50)
                .build();
        when(avatarResourceQueryService.getDefaultSettingResourceAvatarBody()).thenReturn(defaultBody);

        // when
        AvatarBodyUri result = userAvatarQueryService.getDefaultAvatarBodyUri();

        // then
        assertThat(result.getValue()).isEqualTo("default-body-uri");
    }

    @Test
    @DisplayName("findMyAvatarBodies — GT면 바디 리스트를 그대로 반환한다")
    void findMyAvatarBodiesGuestReturnsAllBodies() {
        // given
        ThreadLocalContext.setContext(new AuthContext(new UserId(1L), AuthorityTier.GT));
        AvatarBodyDto bodyDto = AvatarBodyDto.builder()
                .id(1L).name(BODY1).resourceUri("body-uri")
                .obtainableType(ObtainmentType.BASIC).obtainableScore(0)
                .combinable(true).defaultSetting(true).available(true)
                .combinePositionX(0).combinePositionY(0)
                .build();
        when(avatarResourceQueryService.findAllAvatarBodies()).thenReturn(List.of(bodyDto));

        // when
        List<AvatarBodyDto> result = userAvatarQueryService.findMyAvatarBodies();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo(BODY1);
    }

    @Test
    @DisplayName("findMyAvatarBodies — FM이면 활동 점수에 따라 가용 바디를 필터링한다")
    void findMyAvatarBodiesMemberFiltersAvailability() {
        // given
        UserId userId = new UserId(1L);
        ThreadLocalContext.setContext(new AuthContext(userId, AuthorityTier.FM));

        AvatarBodyDto bodyDto = AvatarBodyDto.builder()
                .id(1L).name(BODY1).resourceUri("body-uri")
                .obtainableType(ObtainmentType.DJ_PNT).obtainableScore(100)
                .combinable(true).defaultSetting(false).available(false)
                .combinePositionX(0).combinePositionY(0)
                .build();
        when(avatarResourceQueryService.findAllAvatarBodies()).thenReturn(List.of(bodyDto));

        ActivityData djActivity = ActivityData.create(userId, ActivityType.DJ_PNT, 200);
        when(activityRepository.findAllByUserId(userId)).thenReturn(List.of(djActivity));
        when(userAvatarDomainService.isAvailableBody(eq(ObtainmentType.DJ_PNT), eq(100), anyMap()))
                .thenReturn(true);

        // when
        List<AvatarBodyDto> result = userAvatarQueryService.findMyAvatarBodies();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).isAvailable()).isTrue();
    }
}
