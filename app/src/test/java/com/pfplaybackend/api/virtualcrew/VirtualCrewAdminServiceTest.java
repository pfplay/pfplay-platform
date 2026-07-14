package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.application.port.VirtualCrewOrchestrator;
import com.pfplaybackend.api.virtualcrew.application.service.ActiveDjSnapshotService;
import com.pfplaybackend.api.virtualcrew.application.service.SongPackApplier;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import com.pfplaybackend.api.virtualcrew.domain.exception.VirtualCrewException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** applyConfig 트리거 분기 — 송팩 변경=replaceRoom / 팩 동일=reconcileRoom / OFF=drainRoom. */
@ExtendWith(MockitoExtension.class)
class VirtualCrewAdminServiceTest {

    @Mock private PartyroomVirtualCrewConfigRepository configRepository;
    @Mock private VirtualCrewOrchestrator orchestrator;
    @Mock private ActiveDjSnapshotService activeDjSnapshotService;
    @Mock private VirtualUserPoolService poolService;
    @Mock private BotPoolQueryRepository botPoolQueryRepository;
    @Mock private PartyroomQueryService partyroomQueryService;
    @Mock private SongPackApplier songPackApplier;

    private VirtualCrewAdminService service;

    private static final PartyroomId ROOM = new PartyroomId(7L);

    @BeforeEach
    void setUp() {
        service = new VirtualCrewAdminService(configRepository, orchestrator, activeDjSnapshotService,
                poolService, botPoolQueryRepository, partyroomQueryService, songPackApplier);
        // applyStatus 의 송팩 검증 게이트 통과 스텁 (songPackId != null 케이스)
        PartyroomData room = mock(PartyroomData.class, RETURNS_DEEP_STUBS);
        lenient().when(room.getPlaybackTimeLimit().getMinutes()).thenReturn(5);
        lenient().when(partyroomQueryService.getPartyroomById(any())).thenReturn(room);
        lenient().when(songPackApplier.countPlayableTracks(anyLong(), anyInt())).thenReturn(10);
        lenient().when(configRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void givenExistingConfig(VirtualCrewStatus status, Integer target, Integer djBot, Long packId) {
        PartyroomVirtualCrewConfigData cfg = PartyroomVirtualCrewConfigData.create(ROOM.getId());
        if (status == VirtualCrewStatus.MANAGED) {
            cfg.applyManaged(target, djBot, packId);
        }
        when(configRepository.findByPartyroomId(ROOM.getId())).thenReturn(Optional.of(cfg));
    }

    @Test
    @DisplayName("MANAGED 적용 + 송팩 변경 → replaceRoom (reconcile 아님)")
    void managed_packChanged_triggersReplace() {
        givenExistingConfig(VirtualCrewStatus.MANAGED, 3, 2, 100L);
        service.applyConfig(ROOM, VirtualCrewStatus.MANAGED, 3, 2, 200L);
        verify(orchestrator).replaceRoom(ROOM);
        verify(orchestrator, never()).reconcileRoom(any());
    }

    @Test
    @DisplayName("MANAGED 적용 + 송팩 동일(카운트만 변경) → reconcileRoom (봇 전원 교체 아님)")
    void managed_samePack_triggersReconcile() {
        givenExistingConfig(VirtualCrewStatus.MANAGED, 3, 2, 100L);
        service.applyConfig(ROOM, VirtualCrewStatus.MANAGED, 5, 2, 100L);
        verify(orchestrator).reconcileRoom(ROOM);
        verify(orchestrator, never()).replaceRoom(any());
    }

    @Test
    @DisplayName("신규 config(prev pack=null)에 송팩 지정 → replaceRoom (drain은 무해 no-op)")
    void newConfig_withPack_triggersReplace() {
        when(configRepository.findByPartyroomId(ROOM.getId())).thenReturn(Optional.empty());
        service.applyConfig(ROOM, VirtualCrewStatus.MANAGED, 3, 2, 100L);
        verify(orchestrator).replaceRoom(ROOM);
    }

    @Test
    @DisplayName("MANAGED 적용 + 송팩 null화 → replaceRoom (place는 게이트 skip → 봇 회수 의미)")
    void managed_packNulled_triggersReplace() {
        givenExistingConfig(VirtualCrewStatus.MANAGED, 3, 2, 100L);
        service.applyConfig(ROOM, VirtualCrewStatus.MANAGED, 3, 2, null);
        verify(orchestrator).replaceRoom(ROOM);
    }

    @Test
    @DisplayName("OFF 적용 → drainRoom (기존 동작 불변)")
    void off_triggersDrain() {
        givenExistingConfig(VirtualCrewStatus.MANAGED, 3, 2, 100L);
        service.applyConfig(ROOM, VirtualCrewStatus.OFF, null, null, null);
        verify(orchestrator).drainRoom(ROOM);
        verify(orchestrator, never()).replaceRoom(any());
        verify(orchestrator, never()).reconcileRoom(any());
    }

    @Test
    @DisplayName("replace() — orchestrator.replaceRoom 위임")
    void replace_delegates() {
        service.replace(ROOM);
        verify(orchestrator).replaceRoom(ROOM);
    }

    // ── removeBots (봇 일괄 제거 = 탈퇴 soft-delete) ──

    @Test
    @DisplayName("removeBots — 유효 봇 전원 withdrawBot 위임 + 결과 반환")
    void removeBots_idle_withdrawsEach() {
        when(botPoolQueryRepository.filterBotUserIds(anyList())).thenReturn(List.of(1L, 2L));
        when(botPoolQueryRepository.filterPlacedBotUserIds(anyCollection())).thenReturn(List.of());

        VirtualCrewAdminService.BotRemovalResult result = service.removeBots(List.of(1L, 2L));

        verify(poolService).withdrawBot(new UserId(1L));
        verify(poolService).withdrawBot(new UserId(2L));
        assertThat(result.removed()).isEqualTo(2);
        assertThat(result.removedUserIds()).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("removeBots — 활성 crew 배치 봇 포함 시 409(BOT_PLACED_CANNOT_REMOVE), 전체 거부(withdraw 0건)")
    void removeBots_placed_rejectsAll() {
        when(botPoolQueryRepository.filterBotUserIds(anyList())).thenReturn(List.of(1L, 2L));
        when(botPoolQueryRepository.filterPlacedBotUserIds(anyCollection())).thenReturn(List.of(2L));

        assertThatThrownBy(() -> service.removeBots(List.of(1L, 2L)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(VirtualCrewException.BOT_PLACED_CANNOT_REMOVE.getMessage());

        verify(poolService, never()).withdrawBot(any());
    }

    @Test
    @DisplayName("removeBots — 비봇/미존재/이미탈퇴 id 는 filterBotUserIds 가 걸러 멱등 스킵")
    void removeBots_unknownId_skipped() {
        when(botPoolQueryRepository.filterBotUserIds(anyList())).thenReturn(List.of(1L));
        when(botPoolQueryRepository.filterPlacedBotUserIds(anyCollection())).thenReturn(List.of());

        VirtualCrewAdminService.BotRemovalResult result = service.removeBots(List.of(1L, 999L));

        verify(poolService).withdrawBot(new UserId(1L));
        verify(poolService, never()).withdrawBot(new UserId(999L));
        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.removedUserIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("removeBots — 빈 목록은 no-op(repo 조회조차 안 함)")
    void removeBots_empty_noop() {
        VirtualCrewAdminService.BotRemovalResult result = service.removeBots(List.of());

        assertThat(result.removed()).isZero();
        verify(botPoolQueryRepository, never()).filterBotUserIds(anyList());
        verify(botPoolQueryRepository, never()).filterPlacedBotUserIds(anyCollection());
        verify(poolService, never()).withdrawBot(any());
    }

    @Test
    @DisplayName("removeBots — 유효 봇이 하나도 없으면 no-op(withdraw 0건)")
    void removeBots_noValidBots_noop() {
        when(botPoolQueryRepository.filterBotUserIds(anyList())).thenReturn(List.of());

        VirtualCrewAdminService.BotRemovalResult result = service.removeBots(List.of(999L));

        assertThat(result.removed()).isZero();
        verify(botPoolQueryRepository, never()).filterPlacedBotUserIds(anyCollection());
        verify(poolService, never()).withdrawBot(any());
    }
}
