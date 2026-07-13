package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import com.pfplaybackend.api.administration.domain.event.AnnouncementCancelledEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceEndedEvent;
import com.pfplaybackend.api.administration.domain.event.MaintenanceStartedEvent;
import com.pfplaybackend.api.administration.domain.value.AnnouncementType;
import com.pfplaybackend.api.virtualcrew.adapter.in.event.VirtualCrewMaintenanceListener;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewManagedRoomSweeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("VirtualCrewMaintenanceListener — 점검 이벤트 → sweeper 위임")
class VirtualCrewMaintenanceListenerTest {

    private VirtualCrewManagedRoomSweeper sweeper;
    private VirtualCrewMaintenanceListener listener;

    @BeforeEach
    void setUp() {
        sweeper = mock(VirtualCrewManagedRoomSweeper.class);
        listener = new VirtualCrewMaintenanceListener(sweeper);
    }

    private AnnouncementCancelledEvent cancelledEvent(AnnouncementType type, LocalDateTime startedAt) {
        SystemAnnouncementData entity = mock(SystemAnnouncementData.class);
        given(entity.getType()).willReturn(type);
        given(entity.getMaintenanceStartedAt()).willReturn(startedAt);
        return new AnnouncementCancelledEvent(entity);
    }

    @Test
    @DisplayName("MaintenanceStartedEvent → drainAllManaged")
    void onMaintenanceStarted_drains() {
        listener.onMaintenanceStarted(new MaintenanceStartedEvent(mock(SystemAnnouncementData.class)));
        verify(sweeper).drainAllManaged();
    }

    @Test
    @DisplayName("MaintenanceEndedEvent → placeAllManagedIfActive")
    void onMaintenanceEnded_places() {
        listener.onMaintenanceEnded(new MaintenanceEndedEvent(mock(SystemAnnouncementData.class)));
        verify(sweeper).placeAllManagedIfActive();
    }

    @Test
    @DisplayName("취소: 점검공지 + maintenanceStartedAt != null → placeAllManagedIfActive")
    void onCancelled_activeMaintenance_places() {
        listener.onAnnouncementCancelled(
                cancelledEvent(AnnouncementType.MAINTENANCE_NOTICE, LocalDateTime.now()));
        verify(sweeper).placeAllManagedIfActive();
    }

    @Test
    @DisplayName("취소: 점검공지 + maintenanceStartedAt == null(미시작) → sweeper 미호출")
    void onCancelled_notStarted_noop() {
        listener.onAnnouncementCancelled(
                cancelledEvent(AnnouncementType.MAINTENANCE_NOTICE, null));
        verifyNoInteractions(sweeper);
    }

    @Test
    @DisplayName("취소: 비-점검 공지(EVENT) → sweeper 미호출")
    void onCancelled_nonMaintenance_noop() {
        listener.onAnnouncementCancelled(
                cancelledEvent(AnnouncementType.EVENT, LocalDateTime.now()));
        verifyNoInteractions(sweeper);
    }
}
