package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.operations.application.port.out.MaintenanceGate;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
import com.pfplaybackend.api.virtualdj.application.service.VirtualDjManagedRoomSweeper;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
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

@DisplayName("VirtualDjManagedRoomSweeper — 점검 드레인/부활 공유 스윕")
class VirtualDjManagedRoomSweeperTest {

    private PartyroomVirtualDjConfigRepository configRepository;
    private VirtualDjOrchestrator orchestrator;
    private MaintenanceGate maintenanceGate;
    private VirtualDjManagedRoomSweeper sweeper;

    @BeforeEach
    void setUp() {
        configRepository = mock(PartyroomVirtualDjConfigRepository.class);
        orchestrator = mock(VirtualDjOrchestrator.class);
        maintenanceGate = mock(MaintenanceGate.class);
        sweeper = new VirtualDjManagedRoomSweeper(configRepository, orchestrator, maintenanceGate);
    }

    private PartyroomVirtualDjConfigData managedConfig(long partyroomId) {
        PartyroomVirtualDjConfigData cfg = mock(PartyroomVirtualDjConfigData.class);
        given(cfg.getPartyroomId()).willReturn(partyroomId);
        return cfg;
    }

    @Test
    @DisplayName("drainAllManaged: 2개 MANAGED 방 각각에 drainRoom 호출")
    void drainAllManaged_drainsEachManagedRoom() {
        List<PartyroomVirtualDjConfigData> managed = List.of(managedConfig(10L), managedConfig(20L));
        given(configRepository.findByStatus(VirtualDjStatus.MANAGED)).willReturn(managed);

        sweeper.drainAllManaged();

        verify(orchestrator).drainRoom(new PartyroomId(10L));
        verify(orchestrator).drainRoom(new PartyroomId(20L));
    }

    @Test
    @DisplayName("placeAllManagedIfActive: 점검 아님 → 각 MANAGED 방에 reconcileRoom 호출")
    void placeAllManagedIfActive_notUnderMaintenance_reconcilesEach() {
        List<PartyroomVirtualDjConfigData> managed = List.of(managedConfig(10L), managedConfig(20L));
        given(maintenanceGate.isUnderMaintenance()).willReturn(false);
        given(configRepository.findByStatus(VirtualDjStatus.MANAGED)).willReturn(managed);

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
        verify(configRepository, never()).findByStatus(VirtualDjStatus.MANAGED);
    }

    @Test
    @DisplayName("룸별 예외 격리: 첫 방 reconcileRoom 이 던져도 두 번째 방은 처리된다")
    void placeAllManagedIfActive_perRoomExceptionIsolation() {
        List<PartyroomVirtualDjConfigData> managed = List.of(managedConfig(10L), managedConfig(20L));
        given(maintenanceGate.isUnderMaintenance()).willReturn(false);
        given(configRepository.findByStatus(VirtualDjStatus.MANAGED)).willReturn(managed);
        doThrow(new RuntimeException("boom")).when(orchestrator).reconcileRoom(new PartyroomId(10L));

        sweeper.placeAllManagedIfActive();

        verify(orchestrator).reconcileRoom(new PartyroomId(10L));
        verify(orchestrator).reconcileRoom(new PartyroomId(20L));
    }
}
