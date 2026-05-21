package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.operations.application.service.SystemConfigCache;
import com.pfplaybackend.api.party.application.service.lock.DistributedLockExecutor;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyroomPresenceServiceTest {

    private static final long PARTY_ID = 100L;
    private static final long USER_UID = 200L;
    private static final long CREW_ID = 300L;

    @Mock PartyroomAggregatePort aggregatePort;
    @Mock PartyroomAccessCommandService partyroomAccessCommandService;
    @Mock SystemConfigCache systemConfigCache;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @Mock DistributedLockExecutor distributedLockExecutor;

    PartyroomPresenceService service;
    Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-09T12:00:00Z"), ZoneId.of("UTC"));
        service = new PartyroomPresenceService(aggregatePort, partyroomAccessCommandService,
                systemConfigCache, redisTemplate, distributedLockExecutor, clock);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private PartyroomId roomId() { return new PartyroomId(PARTY_ID); }
    private UserId userId() { return new UserId(USER_UID); }
    private CrewId crewId() { return new CrewId(CREW_ID); }

    private CrewData activeCrew(boolean pending) {
        return CrewData.builder()
                .id(CREW_ID)
                .partyroomId(roomId())
                .userId(userId())
                .isActive(true)
                .pendingExitAt(pending ? LocalDateTime.now(clock) : null)
                .enteredAt(LocalDateTime.now(clock).minusMinutes(5))
                .build();
    }

    @Test
    @DisplayName("markPending — listener인 사용자에게 listener grace로 Redis 키 SET")
    void markPendingListener() {
        when(aggregatePort.findCrew(roomId(), userId())).thenReturn(Optional.of(activeCrew(false)));
        when(aggregatePort.markCrewPending(eq(roomId()), eq(userId()), any(LocalDateTime.class))).thenReturn(1);
        PartyroomPlaybackData playback = mockPlayback(false);
        when(aggregatePort.findPlaybackState(roomId())).thenReturn(playback);
        when(systemConfigCache.getListenerGraceSeconds()).thenReturn(10);

        service.markPending(roomId(), userId());

        verify(valueOps).set(eq("PRESENCE:PENDING:" + PARTY_ID + ":" + CREW_ID), eq(""), eq(10L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("markPending — 현재 DJ에게는 DJ grace로 Redis 키 SET")
    void markPendingCurrentDj() {
        when(aggregatePort.findCrew(roomId(), userId())).thenReturn(Optional.of(activeCrew(false)));
        when(aggregatePort.markCrewPending(eq(roomId()), eq(userId()), any(LocalDateTime.class))).thenReturn(1);
        PartyroomPlaybackData playback = mockPlayback(true);
        when(aggregatePort.findPlaybackState(roomId())).thenReturn(playback);
        when(systemConfigCache.getDjGraceSeconds()).thenReturn(30);

        service.markPending(roomId(), userId());

        verify(valueOps).set(anyString(), eq(""), eq(30L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("markPending — 이미 PENDING인 경우 Redis SET 안 함 (grace 연장 방지)")
    void markPendingAlreadyPending() {
        when(aggregatePort.findCrew(roomId(), userId())).thenReturn(Optional.of(activeCrew(true)));
        when(aggregatePort.markCrewPending(eq(roomId()), eq(userId()), any(LocalDateTime.class))).thenReturn(0);

        service.markPending(roomId(), userId());

        verify(valueOps, never()).set(anyString(), any(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("markPending — crew가 inactive면 no-op")
    void markPendingInactiveCrew() {
        CrewData inactive = CrewData.builder().id(CREW_ID).partyroomId(roomId()).userId(userId())
                .isActive(false).enteredAt(LocalDateTime.now(clock).minusHours(1)).build();
        when(aggregatePort.findCrew(roomId(), userId())).thenReturn(Optional.of(inactive));

        service.markPending(roomId(), userId());

        verify(aggregatePort, never()).markCrewPending(any(), any(), any());
        verify(valueOps, never()).set(anyString(), any(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("clearPending — 전이 발생 시 Redis 키 DEL")
    void clearPendingTransitions() {
        when(aggregatePort.clearCrewPending(roomId(), userId())).thenReturn(1);
        when(aggregatePort.findCrew(roomId(), userId())).thenReturn(Optional.of(activeCrew(false)));

        service.clearPending(roomId(), userId());

        verify(redisTemplate).delete("PRESENCE:PENDING:" + PARTY_ID + ":" + CREW_ID);
    }

    @Test
    @DisplayName("clearPending — 전이 없으면 no-op (Redis 건드리지 않음)")
    void clearPendingNoTransition() {
        when(aggregatePort.clearCrewPending(roomId(), userId())).thenReturn(0);

        service.clearPending(roomId(), userId());

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("forceOffline — PENDING이면 exitInternal 위임 + Redis 키 DEL")
    void forceOfflinePromotes() {
        when(aggregatePort.findCrewById(CREW_ID)).thenReturn(Optional.of(activeCrew(true)));

        service.forceOffline(roomId(), crewId());

        verify(partyroomAccessCommandService).exitInternal(roomId(), userId());
        verify(redisTemplate).delete("PRESENCE:PENDING:" + PARTY_ID + ":" + CREW_ID);
    }

    @Test
    @DisplayName("forceOffline — 이미 reconnect한 (pending=false) 크루는 no-op (race)")
    void forceOfflineAlreadyReconnected() {
        when(aggregatePort.findCrewById(CREW_ID)).thenReturn(Optional.of(activeCrew(false)));

        service.forceOffline(roomId(), crewId());

        verify(partyroomAccessCommandService, never()).exitInternal(any(), any());
        verify(redisTemplate).delete(anyString()); // cleanup only
    }

    @Test
    @DisplayName("forceOffline — 이미 inactive한 크루는 no-op")
    void forceOfflineAlreadyInactive() {
        CrewData inactive = CrewData.builder().id(CREW_ID).partyroomId(roomId()).userId(userId())
                .isActive(false).pendingExitAt(null).enteredAt(LocalDateTime.now(clock).minusHours(1)).build();
        when(aggregatePort.findCrewById(CREW_ID)).thenReturn(Optional.of(inactive));

        service.forceOffline(roomId(), crewId());

        verify(partyroomAccessCommandService, never()).exitInternal(any(), any());
    }

    @Test
    @DisplayName("forceOffline — crew row가 사라졌으면 Redis 키만 정리")
    void forceOfflineMissingCrew() {
        when(aggregatePort.findCrewById(CREW_ID)).thenReturn(Optional.empty());

        service.forceOffline(roomId(), crewId());

        verify(partyroomAccessCommandService, never()).exitInternal(any(), any());
        verify(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("parseKey — 정상 키 파싱")
    void parseKeyValid() {
        PartyroomPresenceService.ParsedPresenceKey parsed =
                PartyroomPresenceService.parseKey("PRESENCE:PENDING:42:99");
        assertThat(parsed).isNotNull();
        assertThat(parsed.partyroomId().getId()).isEqualTo(42L);
        assertThat(parsed.crewId().getId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("parseKey — 잘못된 prefix면 null")
    void parseKeyWrongPrefix() {
        assertThat(PartyroomPresenceService.parseKey("TASK:WAIT:1")).isNull();
        assertThat(PartyroomPresenceService.parseKey(null)).isNull();
        assertThat(PartyroomPresenceService.parseKey("PRESENCE:PENDING:notnum:99")).isNull();
        assertThat(PartyroomPresenceService.parseKey("PRESENCE:PENDING:42")).isNull();
    }

    @Test
    @DisplayName("reconcileStalePending — stale 행을 forceOffline로 처리")
    void reconcileSweep() {
        when(systemConfigCache.getDjGraceSeconds()).thenReturn(30);
        when(systemConfigCache.getListenerGraceSeconds()).thenReturn(10);
        when(aggregatePort.findStalePendingCrews(any(LocalDateTime.class)))
                .thenReturn(java.util.List.of(activeCrew(true)));
        when(aggregatePort.findCrewById(CREW_ID)).thenReturn(Optional.of(activeCrew(true)));
        // distributed lock executes the action immediately
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Supplier<Void> action = invocation.getArgument(1);
            action.get();
            return null;
        }).when(distributedLockExecutor).performTaskWithLock(anyString(), any());

        service.reconcileStalePending();

        verify(partyroomAccessCommandService, times(1)).exitInternal(roomId(), userId());
    }

    /**
     * SE10 backstop characterization (#225 #209).
     *
     * <p>STOMP heartbeat is only a fast-path: on mobile-network death with no TCP
     * FIN the disconnect can take ~15-20s to surface (or never, if heartbeat
     * detection fails entirely). The AUTHORITATIVE ghost upper bound is this
     * reconcile cron. This test simulates "heartbeat-driven disconnect NEVER
     * fired and the crew NEVER reconnected": the crew sits PENDING_EXIT with a
     * {@code pending_exit_at} older than {@code now - (maxGrace +
     * RECONCILE_BUFFER_SECONDS)}. We assert the cron still reclaims it —
     * computing the threshold from grace+buffer and forcing OFFLINE
     * ({@code exitInternal}) for that stale crew. If this ever fails, the cron is
     * NOT the backstop the WebSocketConfig comment promises.
     */
    @Test
    @DisplayName("reconcileStalePending — heartbeat 미발화 + 미재접속 PENDING은 cron이 상한선으로 회수 (SE10)")
    void reconcileIsTheGhostUpperBoundWhenHeartbeatNeverFires() {
        int djGrace = 30;
        int listenerGrace = 10;
        when(systemConfigCache.getDjGraceSeconds()).thenReturn(djGrace);
        when(systemConfigCache.getListenerGraceSeconds()).thenReturn(listenerGrace);

        // pending_exit_at well past grace(max=30s) + RECONCILE_BUFFER(30s): the
        // heartbeat fast-path provably never reclaimed this crew.
        LocalDateTime staleSince = LocalDateTime.now(clock).minusSeconds(djGrace + 30 + 5);
        CrewData staleCrew = CrewData.builder()
                .id(CREW_ID)
                .partyroomId(roomId())
                .userId(userId())
                .isActive(true)
                .pendingExitAt(staleSince)
                .enteredAt(LocalDateTime.now(clock).minusHours(1))
                .build();

        org.mockito.ArgumentCaptor<LocalDateTime> thresholdCaptor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        when(aggregatePort.findStalePendingCrews(any(LocalDateTime.class)))
                .thenReturn(java.util.List.of(staleCrew));
        when(aggregatePort.findCrewById(CREW_ID)).thenReturn(Optional.of(staleCrew));
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Supplier<Void> action = invocation.getArgument(1);
            action.get();
            return null;
        }).when(distributedLockExecutor).performTaskWithLock(anyString(), any());

        service.reconcileStalePending();

        // threshold = now - (max(djGrace,listenerGrace) + RECONCILE_BUFFER_SECONDS=30)
        verify(aggregatePort).findStalePendingCrews(thresholdCaptor.capture());
        LocalDateTime expectedThreshold =
                LocalDateTime.now(clock).minusSeconds(djGrace + 30L);
        assertThat(thresholdCaptor.getValue()).isEqualTo(expectedThreshold);
        // the stale (older-than-threshold) crew is the upper-bound reclaim:
        assertThat(staleCrew.getPendingExitAt()).isBefore(expectedThreshold);
        // cron forces OFFLINE — exitInternal is the authoritative deactivation.
        verify(partyroomAccessCommandService, times(1)).exitInternal(roomId(), userId());
    }

    private PartyroomPlaybackData mockPlayback(boolean currentDj) {
        PartyroomPlaybackData playback = org.mockito.Mockito.mock(PartyroomPlaybackData.class);
        lenient().when(playback.isActivated()).thenReturn(currentDj);
        lenient().when(playback.isCurrentDj(any(CrewId.class))).thenReturn(currentDj);
        return playback;
    }
}
