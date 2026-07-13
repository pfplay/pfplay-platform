package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.application.port.VirtualCrewOrchestrator;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewManagedRoomSweeper;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("VirtualCrewManagedRoomSweeper — 점검 드레인/부활 공유 스윕")
class VirtualCrewManagedRoomSweeperTest {

    private PartyroomVirtualCrewConfigRepository configRepository;
    private VirtualCrewOrchestrator orchestrator;
    private MaintenanceGate maintenanceGate;
    private VirtualCrewManagedRoomSweeper sweeper;

    @BeforeEach
    void setUp() {
        configRepository = mock(PartyroomVirtualCrewConfigRepository.class);
        orchestrator = mock(VirtualCrewOrchestrator.class);
        maintenanceGate = mock(MaintenanceGate.class);
        sweeper = new VirtualCrewManagedRoomSweeper(configRepository, orchestrator, maintenanceGate);
        // 프로덕션에선 @Lazy self 프록시가 룸별 REQUIRES_NEW 경계를 제공한다. 단위 테스트에는 프록시가
        // 없으므로 self 를 자기 자신으로 주입한다 — runRoomOpInNewTransaction 이 그대로 action 을 위임한다.
        sweeper.setSelf(sweeper);
    }

    private PartyroomVirtualCrewConfigData managedConfig(long partyroomId) {
        PartyroomVirtualCrewConfigData cfg = mock(PartyroomVirtualCrewConfigData.class);
        given(cfg.getPartyroomId()).willReturn(partyroomId);
        return cfg;
    }

    @Test
    @DisplayName("drainAllManaged: 2개 MANAGED 방 각각에 drainRoom 호출")
    void drainAllManaged_drainsEachManagedRoom() {
        List<PartyroomVirtualCrewConfigData> managed = List.of(managedConfig(10L), managedConfig(20L));
        given(configRepository.findByStatus(VirtualCrewStatus.MANAGED)).willReturn(managed);

        sweeper.drainAllManaged();

        verify(orchestrator).drainRoom(new PartyroomId(10L));
        verify(orchestrator).drainRoom(new PartyroomId(20L));
    }

    @Test
    @DisplayName("placeAllManagedIfActive: 점검 아님 → 각 MANAGED 방에 reconcileRoom 호출")
    void placeAllManagedIfActive_notUnderMaintenance_reconcilesEach() {
        List<PartyroomVirtualCrewConfigData> managed = List.of(managedConfig(10L), managedConfig(20L));
        given(maintenanceGate.isUnderMaintenance()).willReturn(false);
        given(configRepository.findByStatus(VirtualCrewStatus.MANAGED)).willReturn(managed);

        sweeper.placeAllManagedIfActive();

        verify(orchestrator).reconcileRoom(new PartyroomId(10L));
        verify(orchestrator).reconcileRoom(new PartyroomId(20L));
    }

    @Test
    @DisplayName("placeAllManagedIfActive: 점검 중 → 게이트가 차단, orchestrator 미호출")
    void placeAllManagedIfActive_underMaintenance_skips() {
        given(maintenanceGate.isUnderMaintenance()).willReturn(true);

        sweeper.placeAllManagedIfActive();

        verifyNoInteractions(orchestrator);
        // 게이트가 먼저 차단하므로 repo 조회조차 하지 않는다.
        verify(configRepository, never()).findByStatus(VirtualCrewStatus.MANAGED);
    }

    @Test
    @DisplayName("룸별 예외 격리: 첫 방 reconcileRoom 이 던져도 두 번째 방은 처리된다")
    void placeAllManagedIfActive_perRoomExceptionIsolation() {
        List<PartyroomVirtualCrewConfigData> managed = List.of(managedConfig(10L), managedConfig(20L));
        given(maintenanceGate.isUnderMaintenance()).willReturn(false);
        given(configRepository.findByStatus(VirtualCrewStatus.MANAGED)).willReturn(managed);
        doThrow(new RuntimeException("boom")).when(orchestrator).reconcileRoom(new PartyroomId(10L));

        sweeper.placeAllManagedIfActive();

        verify(orchestrator).reconcileRoom(new PartyroomId(10L));
        verify(orchestrator).reconcileRoom(new PartyroomId(20L));
    }
}
