package com.pfplaybackend.api.administration.adapter.out.maintenance;

import com.pfplaybackend.api.administration.adapter.out.persistence.SystemAnnouncementRepository;
import com.pfplaybackend.api.administration.domain.entity.data.SystemAnnouncementData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 점검 게이트의 단일 진실원천 = "현재 ACTIVE 인 system_announcement 점검"
 * ({@code findCurrentMaintenance}). 죽어 있던 system_config maintenance.enabled
 * 키 대신 이 어댑터가 필터의 소스가 된다. 30s 캐시는 SystemConfigCache 와 동일 정책.
 */
@ExtendWith(MockitoExtension.class)
class ActiveMaintenanceGateTest {

    @Mock
    SystemAnnouncementRepository repository;

    private SystemAnnouncementData activeWithMessage(String messageKo) {
        SystemAnnouncementData a = mock(SystemAnnouncementData.class);
        when(a.getMessageKo()).thenReturn(messageKo);
        return a;
    }

    @Test
    void under_maintenance_with_message_when_active_announcement_present() {
        SystemAnnouncementData active = activeWithMessage("점검 중입니다");
        when(repository.findCurrentMaintenance()).thenReturn(Optional.of(active));
        ActiveMaintenanceGate gate = new ActiveMaintenanceGate(repository, fixed());

        assertThat(gate.isUnderMaintenance()).isTrue();
        assertThat(gate.getMaintenanceMessage()).isEqualTo("점검 중입니다");
    }

    @Test
    void not_under_maintenance_when_no_active_announcement() {
        when(repository.findCurrentMaintenance()).thenReturn(Optional.empty());
        ActiveMaintenanceGate gate = new ActiveMaintenanceGate(repository, fixed());

        assertThat(gate.isUnderMaintenance()).isFalse();
    }

    @Test
    void blank_message_falls_back_to_default() {
        SystemAnnouncementData active = activeWithMessage("  ");
        when(repository.findCurrentMaintenance()).thenReturn(Optional.of(active));
        ActiveMaintenanceGate gate = new ActiveMaintenanceGate(repository, fixed());

        assertThat(gate.isUnderMaintenance()).isTrue();
        assertThat(gate.getMaintenanceMessage()).isNotBlank();
    }

    @Test
    void second_call_within_ttl_does_not_hit_repository() {
        when(repository.findCurrentMaintenance()).thenReturn(Optional.empty());
        ActiveMaintenanceGate gate = new ActiveMaintenanceGate(repository, fixed());

        gate.isUnderMaintenance();
        gate.isUnderMaintenance();

        verify(repository, times(1)).findCurrentMaintenance();
    }

    @Test
    void call_after_ttl_refetches() {
        SystemAnnouncementData active = activeWithMessage("점검");
        when(repository.findCurrentMaintenance())
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(active));
        MutableClock clock = new MutableClock(Instant.parse("2026-05-25T00:00:00Z"));
        ActiveMaintenanceGate gate = new ActiveMaintenanceGate(repository, clock);

        assertThat(gate.isUnderMaintenance()).isFalse();
        clock.advanceSeconds(31);
        assertThat(gate.isUnderMaintenance()).isTrue();

        verify(repository, times(2)).findCurrentMaintenance();
    }

    private static Clock fixed() {
        return Clock.fixed(Instant.parse("2026-05-25T00:00:00Z"), ZoneOffset.UTC);
    }

    /** Test-only mutable clock. */
    static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
    }
}
