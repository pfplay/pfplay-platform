package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.application.service.BotPlacementService;
import com.pfplaybackend.api.virtualcrew.application.service.VirtualCrewOrchestratorImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** replaceRoom — 락 1회 안에서 drain→place 순차(언락 프리미티브 직접 호출) 검증. */
@ExtendWith(MockitoExtension.class)
class VirtualCrewOrchestratorImplTest {

    @Mock private DistributedLockExecutor lock;
    @Mock private BotPlacementService botPlacementService;
    @InjectMocks private VirtualCrewOrchestratorImpl orchestrator;

    @Test
    @DisplayName("replaceRoom — 룸 락 1회 획득 안에서 drainResources → placeToTarget 순서로 호출한다")
    void replaceRoom_drainThenPlace_underSingleLock() {
        PartyroomId roomId = new PartyroomId(7L);
        // ⚠️ performTaskWithLock 는 void 반환(Supplier<Void> 파라미터) — when(...)은 컴파일 불가, doAnswer 필수
        doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get())
                .when(lock).performTaskWithLock(eq("virtualcrew:7"), any());

        orchestrator.replaceRoom(roomId);

        verify(lock, times(1)).performTaskWithLock(eq("virtualcrew:7"), any());
        InOrder inOrder = inOrder(botPlacementService);
        inOrder.verify(botPlacementService).drainResources(roomId);
        inOrder.verify(botPlacementService).placeToTarget(roomId);
        verifyNoMoreInteractions(botPlacementService);
    }
}
