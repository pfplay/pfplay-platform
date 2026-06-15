package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.service.BotIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link BotIdentityResolver} 단위 테스트 — crewId 로 봇(is_dummy) 여부를 판별한다.
 *
 * <p>채팅 관찰 루프에서 봇 자신의 발화에 반응하지 않도록 하는 loop guard 로 쓰인다.
 * 캐싱 없음(spec §11.6 — simple first).
 */
@ExtendWith(MockitoExtension.class)
class BotIdentityResolverTest {

    @Mock
    CrewRepository crewRepository;

    @Mock
    BotPoolQueryRepository botPoolQueryRepository;

    BotIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BotIdentityResolver(crewRepository, botPoolQueryRepository);
    }

    @Test
    @DisplayName("봇 crew → true")
    void bot_crew_returns_true() {
        long crewId = 100L;
        long botUserId = 7001L;
        CrewData crew = CrewData.builder().userId(new UserId(botUserId)).build();
        when(crewRepository.findById(crewId)).thenReturn(Optional.of(crew));
        when(botPoolQueryRepository.filterBotUserIds(List.of(botUserId)))
                .thenReturn(List.of(botUserId));

        assertThat(resolver.isBotCrew(crewId)).isTrue();
    }

    @Test
    @DisplayName("비-봇 crew (filter 빈 결과) → false")
    void non_bot_crew_returns_false() {
        long crewId = 200L;
        long humanUserId = 42L;
        CrewData crew = CrewData.builder().userId(new UserId(humanUserId)).build();
        when(crewRepository.findById(crewId)).thenReturn(Optional.of(crew));
        when(botPoolQueryRepository.filterBotUserIds(List.of(humanUserId)))
                .thenReturn(List.of());

        assertThat(resolver.isBotCrew(crewId)).isFalse();
    }

    @Test
    @DisplayName("crew 없음 → false")
    void crew_not_found_returns_false() {
        long crewId = 999L;
        when(crewRepository.findById(crewId)).thenReturn(Optional.empty());

        assertThat(resolver.isBotCrew(crewId)).isFalse();
    }
}
