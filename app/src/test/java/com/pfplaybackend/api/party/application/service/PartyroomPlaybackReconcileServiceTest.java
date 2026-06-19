package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.party.application.port.out.PlaybackControlPort;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartyroomPlaybackReconcileServiceTest {

    private final PartyroomId room = new PartyroomId(1L);

    private DjData dj(long crewId) {
        DjData d = mock(DjData.class);
        when(d.getCrewId()).thenReturn(new CrewId(crewId));
        return d;
    }

    private CrewData crew(long id, boolean active) {
        return CrewData.builder().id(id).partyroomId(room).gradeType(GradeType.LISTENER).isActive(active).build();
    }

    // ───────── Healer: orphan ─────────

    @Test
    @DisplayName("orphan=현재DJ → removeDjs + skipPlayback")
    void healOrphanCurrentDjRemovesAndSkips() {
        PartyroomAggregatePort port = mock(PartyroomAggregatePort.class);
        PlaybackControlPort playback = mock(PlaybackControlPort.class);
        PlaybackQueryService pq = mock(PlaybackQueryService.class);
        PlaybackReconcileHealer healer = new PlaybackReconcileHealer(port, playback, pq);

        DjData orphan = dj(67);
        when(port.findDjsOrdered(room)).thenReturn(List.of(orphan));
        when(port.findCrewsByIds(any())).thenReturn(List.of(crew(67, false)));
        PartyroomPlaybackData pp = mock(PartyroomPlaybackData.class);
        when(pp.isActivated()).thenReturn(true);
        when(pp.isCurrentDj(new CrewId(67))).thenReturn(true);
        when(port.findPlaybackState(room)).thenReturn(pp);

        healer.healOrphan(room);

        verify(port).removeDjs(argThat(l -> l.size() == 1 && l.get(0).getCrewId().equals(new CrewId(67))));
        verify(playback).skipPlayback(room);
    }

    @Test
    @DisplayName("orphan=비현재DJ → removeDjs, skipPlayback 미호출")
    void healOrphanNonCurrentRemovesNoSkip() {
        PartyroomAggregatePort port = mock(PartyroomAggregatePort.class);
        PlaybackControlPort playback = mock(PlaybackControlPort.class);
        PlaybackReconcileHealer healer = new PlaybackReconcileHealer(port, playback, mock(PlaybackQueryService.class));

        DjData d67 = dj(67);
        when(port.findDjsOrdered(room)).thenReturn(List.of(d67));
        when(port.findCrewsByIds(any())).thenReturn(List.of(crew(67, false)));
        PartyroomPlaybackData pp = mock(PartyroomPlaybackData.class);
        when(pp.isActivated()).thenReturn(true);
        when(pp.isCurrentDj(new CrewId(67))).thenReturn(false);
        when(port.findPlaybackState(room)).thenReturn(pp);

        healer.healOrphan(room);

        verify(port).removeDjs(any());
        verify(playback, never()).skipPlayback(any());
    }

    @Test
    @DisplayName("orphan 없음(전부 active) → 멱등 no-op")
    void healOrphanNoneIsNoOp() {
        PartyroomAggregatePort port = mock(PartyroomAggregatePort.class);
        PlaybackControlPort playback = mock(PlaybackControlPort.class);
        PlaybackReconcileHealer healer = new PlaybackReconcileHealer(port, playback, mock(PlaybackQueryService.class));

        DjData d67 = dj(67);
        when(port.findDjsOrdered(room)).thenReturn(List.of(d67));
        when(port.findCrewsByIds(any())).thenReturn(List.of(crew(67, true)));

        healer.healOrphan(room);

        verify(port, never()).removeDjs(any());
        verify(playback, never()).skipPlayback(any());
    }

    // ───────── Healer: stuck ─────────

    @Test
    @DisplayName("stuck(endTime<threshold) → skipPlayback")
    void healStuckExpiredSkips() {
        PartyroomAggregatePort port = mock(PartyroomAggregatePort.class);
        PlaybackControlPort playback = mock(PlaybackControlPort.class);
        PlaybackQueryService pq = mock(PlaybackQueryService.class);
        PlaybackReconcileHealer healer = new PlaybackReconcileHealer(port, playback, pq);

        PartyroomPlaybackData pp = mock(PartyroomPlaybackData.class);
        when(pp.isActivated()).thenReturn(true);
        when(pp.getCurrentPlaybackId()).thenReturn(new PlaybackId(5L));
        when(port.findPlaybackState(room)).thenReturn(pp);
        PlaybackData track = mock(PlaybackData.class);
        when(track.getEndTime()).thenReturn(1000L);
        when(pq.getPlaybackById(new PlaybackId(5L))).thenReturn(track);

        healer.healStuck(room, 2000L); // endTime 1000 < threshold 2000

        verify(playback).skipPlayback(room);
    }

    @Test
    @DisplayName("stuck(current playback null) → skipPlayback")
    void healStuckNullPlaybackSkips() {
        PartyroomAggregatePort port = mock(PartyroomAggregatePort.class);
        PlaybackControlPort playback = mock(PlaybackControlPort.class);
        PlaybackReconcileHealer healer = new PlaybackReconcileHealer(port, playback, mock(PlaybackQueryService.class));

        PartyroomPlaybackData pp = mock(PartyroomPlaybackData.class);
        when(pp.isActivated()).thenReturn(true);
        when(pp.getCurrentPlaybackId()).thenReturn(null);
        when(port.findPlaybackState(room)).thenReturn(pp);

        healer.healStuck(room, 2000L);

        verify(playback).skipPlayback(room);
    }

    @Test
    @DisplayName("not stuck(endTime>=threshold) → 멱등 no-op")
    void healStuckFreshNoOp() {
        PartyroomAggregatePort port = mock(PartyroomAggregatePort.class);
        PlaybackControlPort playback = mock(PlaybackControlPort.class);
        PlaybackQueryService pq = mock(PlaybackQueryService.class);
        PlaybackReconcileHealer healer = new PlaybackReconcileHealer(port, playback, pq);

        PartyroomPlaybackData pp = mock(PartyroomPlaybackData.class);
        when(pp.isActivated()).thenReturn(true);
        when(pp.getCurrentPlaybackId()).thenReturn(new PlaybackId(5L));
        when(port.findPlaybackState(room)).thenReturn(pp);
        PlaybackData track = mock(PlaybackData.class);
        when(track.getEndTime()).thenReturn(5000L);
        when(pq.getPlaybackById(new PlaybackId(5L))).thenReturn(track);

        healer.healStuck(room, 2000L); // 5000 >= 2000 → 새 트랙

        verify(playback, never()).skipPlayback(any());
    }

    @Test
    @DisplayName("not activated → no-op")
    void healStuckDeactivatedNoOp() {
        PartyroomAggregatePort port = mock(PartyroomAggregatePort.class);
        PlaybackControlPort playback = mock(PlaybackControlPort.class);
        PlaybackReconcileHealer healer = new PlaybackReconcileHealer(port, playback, mock(PlaybackQueryService.class));

        PartyroomPlaybackData pp = mock(PartyroomPlaybackData.class);
        when(pp.isActivated()).thenReturn(false);
        when(port.findPlaybackState(room)).thenReturn(pp);

        healer.healStuck(room, 2000L);

        verify(playback, never()).skipPlayback(any());
    }

    // ───────── Service: sweep wiring ─────────

    @Test
    @DisplayName("sweep — 탐지된 룸마다 락 안에서 healer 호출")
    void sweepsInvokeHealerUnderLock() {
        PartyroomAggregatePort port = mock(PartyroomAggregatePort.class);
        PlaybackReconcileHealer healer = mock(PlaybackReconcileHealer.class);
        DistributedLockExecutor lock = mock(DistributedLockExecutor.class);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1_000_000L);
        // 락 실행기는 supplier 를 그대로 실행
        doAnswer(inv -> { ((Supplier<?>) inv.getArgument(1)).get(); return null; })
                .when(lock).performTaskWithLock(anyString(), any());
        when(port.findPartyroomIdsWithInactiveCrewDj()).thenReturn(List.of(room));
        when(port.findStuckActivatedPartyroomIds(anyLong())).thenReturn(List.of(room));

        new PartyroomPlaybackReconcileService(port, healer, lock, clock).reconcile();

        verify(healer).healOrphan(room);
        verify(healer).healStuck(eq(room), eq(1_000_000L - PartyroomPlaybackReconcileService.STUCK_BUFFER_MS));
    }
}
