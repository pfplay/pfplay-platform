package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.avatar.application.dto.AvatarBodyDto;
import com.pfplaybackend.api.avatar.application.port.in.AvatarCatalogQueryUseCase;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualcrew.application.dto.BotRosterRow;
import com.pfplaybackend.api.virtualcrew.application.service.BotAvatarAdminService;
import com.pfplaybackend.api.virtualcrew.application.service.BotAvatarAssigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * BotAvatarAdminService 단위 테스트 — 카탈로그 매핑 / 로스터·개별·일괄 위임 검증(mock).
 */
class BotAvatarAdminServiceTest {

    private BotPoolQueryRepository botPoolQueryRepository;
    private AvatarCatalogQueryUseCase catalog;
    private BotAvatarAssigner assigner;
    private BotAvatarAdminService service;

    @BeforeEach
    void setUp() {
        botPoolQueryRepository = mock(BotPoolQueryRepository.class);
        catalog = mock(AvatarCatalogQueryUseCase.class);
        assigner = mock(BotAvatarAssigner.class);
        service = new BotAvatarAdminService(botPoolQueryRepository, catalog, assigner);
    }

    @Test
    void catalog_은_published_바디를_CatalogItem_으로_매핑한다() {
        given(catalog.findPublishedBodies()).willReturn(List.of(
                AvatarBodyDto.builder()
                        .name("바디A").resourceUri("https://cdn/a.png").iconUri(null)
                        .combinable(true).obtainableType(ObtainmentType.BASIC).build(),
                AvatarBodyDto.builder()
                        .name("바디B").resourceUri("https://cdn/b.png").iconUri("https://cdn/b.icon.png")
                        .combinable(false).obtainableType(ObtainmentType.DJ_PNT).build()));

        List<BotAvatarAdminService.CatalogItem> items = service.catalog();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).bodyUri()).isEqualTo("https://cdn/a.png");
        assertThat(items.get(0).name()).isEqualTo("바디A");
        assertThat(items.get(0).thumbnailUri()).isEqualTo("https://cdn/a.png");
        assertThat(items.get(0).combinable()).isTrue();
        assertThat(items.get(0).obtainableType()).isEqualTo("BASIC");
        assertThat(items.get(1).combinable()).isFalse();
        assertThat(items.get(1).obtainableType()).isEqualTo("DJ_PNT");
    }

    @Test
    void catalog_은_obtainableType_null_을_null_로_매핑한다() {
        given(catalog.findPublishedBodies()).willReturn(List.of(
                AvatarBodyDto.builder()
                        .name("바디").resourceUri("https://cdn/a.png").iconUri(null)
                        .combinable(true).obtainableType(null).build()));

        assertThat(service.catalog().get(0).obtainableType()).isNull();
    }

    @Test
    void roster_는_repository_findRoster_에_위임한다() {
        List<BotRosterRow> rows = List.of(
                new BotRosterRow(1L, "봇", "https://cdn/body.png", "https://cdn/icon.png", 7L, "룸", 3L, "DJ 챌린저"));
        given(botPoolQueryRepository.findRoster()).willReturn(rows);

        assertThat(service.roster()).isEqualTo(rows);
        verify(botPoolQueryRepository).findRoster();
    }

    @Test
    void setIndividual_은_assigner_assignOne_에_위임한다() {
        service.setIndividual(new UserId(5L), "https://cdn/body.png");
        verify(assigner).assignOne(eq(new UserId(5L)), eq("https://cdn/body.png"));
    }

    @Test
    void distribute_는_실제_봇만_사전필터한_뒤_UserId_로_변환해_assigner_에_위임하고_결과를_반환한다() {
        // 후보 [1,2,3] 중 사전필터가 [1,2] 만 봇으로 추린다(3 은 비-봇/미존재 → 격리).
        given(botPoolQueryRepository.filterBotUserIds(List.of(1L, 2L, 3L)))
                .willReturn(List.of(1L, 2L));
        List<BotAvatarAssigner.Assigned> result = List.of(
                new BotAvatarAssigner.Assigned(1L, "https://cdn/body1.png"),
                new BotAvatarAssigner.Assigned(2L, "https://cdn/body2.png"));
        given(assigner.distribute(
                List.of(new UserId(1L), new UserId(2L)),
                List.of("https://cdn/body1.png", "https://cdn/body2.png")))
                .willReturn(result);

        List<BotAvatarAssigner.Assigned> out = service.distribute(
                List.of(1L, 2L, 3L),
                List.of("https://cdn/body1.png", "https://cdn/body2.png"));

        assertThat(out).isEqualTo(result);
        verify(botPoolQueryRepository).filterBotUserIds(List.of(1L, 2L, 3L));
        verify(assigner).distribute(
                List.of(new UserId(1L), new UserId(2L)),
                List.of("https://cdn/body1.png", "https://cdn/body2.png"));
    }
}
