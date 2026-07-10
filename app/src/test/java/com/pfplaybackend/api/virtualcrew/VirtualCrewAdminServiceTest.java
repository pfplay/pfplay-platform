package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.application.port.VirtualCrewOrchestrator;
import com.pfplaybackend.api.virtualcrew.application.service.ActiveDjSnapshotService;
import com.pfplaybackend.api.virtualcrew.application.service.SongPackApplier;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewAdminService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

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
}
