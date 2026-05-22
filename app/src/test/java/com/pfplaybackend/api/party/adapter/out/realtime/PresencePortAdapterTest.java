package com.pfplaybackend.api.party.adapter.out.realtime;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PartyroomQueryPort;
import com.pfplaybackend.api.party.application.service.PartyroomPresenceService;
import com.pfplaybackend.api.party.application.service.UserSessionRegistry;
import com.pfplaybackend.api.party.application.service.UserSessionRegistry.UnregisterResult;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.realtime.port.SessionCachePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresencePortAdapterTest {

    @Mock UserSessionRegistry userSessionRegistry;
    @Mock PartyroomQueryPort partyroomQueryPort;
    @Mock PartyroomPresenceService presenceService;
    @Mock SessionCachePort sessionCachePort;

    @InjectMocks PresencePortAdapter adapter;

    private static final String SID = "sess-1";
    private static final UserId UID = new UserId(42L);
    private static final long ROOM_ID = 777L;

    private ActivePartyroomDto activeRoom() {
        return new ActivePartyroomDto(ROOM_ID, false, 1L, false, null, null);
    }

    @Test
    @DisplayName("connected: 레지스트리 uid resolve + 활성 룸 있으면 clearPending")
    void onSessionConnected_resolvesViaRegistryAndQueryPort_clearsPending() {
        when(userSessionRegistry.findUserBySession(SID)).thenReturn(Optional.of(UID));
        when(partyroomQueryPort.getActivePartyroomByUserId(UID)).thenReturn(Optional.of(activeRoom()));

        adapter.onSessionConnected(SID);

        verify(presenceService).clearPending(new PartyroomId(ROOM_ID), UID);
        verifyNoInteractions(sessionCachePort);
    }

    @Test
    @DisplayName("connected: 레지스트리에 uid 없으면 silent no-op")
    void onSessionConnected_unknownSession_isSilentNoOp() {
        when(userSessionRegistry.findUserBySession(SID)).thenReturn(Optional.empty());

        adapter.onSessionConnected(SID);

        verifyNoInteractions(presenceService);
        verifyNoInteractions(partyroomQueryPort);
        verifyNoInteractions(sessionCachePort);
    }

    @Test
    @DisplayName("connected: uid는 있지만 활성 룸 없으면 silent no-op")
    void onSessionConnected_noActiveRoom_isSilentNoOp() {
        when(userSessionRegistry.findUserBySession(SID)).thenReturn(Optional.of(UID));
        when(partyroomQueryPort.getActivePartyroomByUserId(UID)).thenReturn(Optional.empty());

        adapter.onSessionConnected(SID);

        verify(presenceService, never()).clearPending(any(), any());
        verifyNoInteractions(sessionCachePort);
    }

    @Test
    @DisplayName("disconnected: 마지막 세션이면 활성 룸 resolve 후 markPending")
    void onSessionDisconnected_lastSession_marksPending() {
        when(userSessionRegistry.unregister(SID))
                .thenReturn(new UnregisterResult(Optional.of(UID), true));
        when(partyroomQueryPort.getActivePartyroomByUserId(UID)).thenReturn(Optional.of(activeRoom()));

        adapter.onSessionDisconnected(SID);

        verify(presenceService).markPending(new PartyroomId(ROOM_ID), UID);
        verifyNoInteractions(sessionCachePort);
    }

    @Test
    @DisplayName("disconnected: 마지막 세션 아니면 멀티탭 → no-op")
    void onSessionDisconnected_notLastSession_isSilentNoOp() {
        when(userSessionRegistry.unregister(SID))
                .thenReturn(new UnregisterResult(Optional.of(UID), false));

        adapter.onSessionDisconnected(SID);

        verifyNoInteractions(presenceService);
        verifyNoInteractions(partyroomQueryPort);
        verifyNoInteractions(sessionCachePort);
    }

    @Test
    @DisplayName("disconnected: 알 수 없는 세션이면 silent no-op")
    void onSessionDisconnected_unknownSession_isSilentNoOp() {
        when(userSessionRegistry.unregister(SID))
                .thenReturn(new UnregisterResult(Optional.empty(), false));

        adapter.onSessionDisconnected(SID);

        verifyNoInteractions(presenceService);
        verifyNoInteractions(partyroomQueryPort);
        verifyNoInteractions(sessionCachePort);
    }

    @Test
    @DisplayName("disconnected: 마지막 세션이지만 활성 룸 없으면 silent no-op")
    void onSessionDisconnected_lastSessionNoActiveRoom_isSilentNoOp() {
        when(userSessionRegistry.unregister(SID))
                .thenReturn(new UnregisterResult(Optional.of(UID), true));
        when(partyroomQueryPort.getActivePartyroomByUserId(UID)).thenReturn(Optional.empty());

        adapter.onSessionDisconnected(SID);

        verify(presenceService, never()).markPending(any(), any());
        verifyNoInteractions(sessionCachePort);
    }
}
