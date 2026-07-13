package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewBootReviver;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewManagedRoomSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("VirtualCrewBootReviver — 부팅 시 부활 위임")
class VirtualCrewBootReviverTest {

    @Test
    @DisplayName("reviveOnBoot → placeAllManagedIfActive 1회 위임")
    void reviveOnBoot_delegates() {
        VirtualCrewManagedRoomSweeper sweeper = mock(VirtualCrewManagedRoomSweeper.class);
        VirtualCrewBootReviver reviver = new VirtualCrewBootReviver(sweeper);

        reviver.reviveOnBoot();

        verify(sweeper).placeAllManagedIfActive();
    }
}
