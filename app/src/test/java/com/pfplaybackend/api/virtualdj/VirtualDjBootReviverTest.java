package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.virtualdj.application.service.VirtualDjBootReviver;
import com.pfplaybackend.api.virtualdj.application.service.VirtualDjManagedRoomSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("VirtualDjBootReviver — 부팅 시 부활 위임")
class VirtualDjBootReviverTest {

    @Test
    @DisplayName("reviveOnBoot → placeAllManagedIfActive 1회 위임")
    void reviveOnBoot_delegates() {
        VirtualDjManagedRoomSweeper sweeper = mock(VirtualDjManagedRoomSweeper.class);
        VirtualDjBootReviver reviver = new VirtualDjBootReviver(sweeper);

        reviver.reviveOnBoot();

        verify(sweeper).placeAllManagedIfActive();
    }
}
