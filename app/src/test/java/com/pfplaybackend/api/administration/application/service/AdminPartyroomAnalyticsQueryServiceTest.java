package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminDjHistoryItemResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.PartyroomAnalyticsResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogAnalyticsRepository;
import com.pfplaybackend.api.administration.application.dto.DailyAttendanceBucket;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPartyroomAnalyticsQueryServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Mock private PartyroomAggregatePort aggregatePort;
    @Mock private UserActivityLogAnalyticsRepository activityRepo;
    @Mock private MemberRepository memberRepository;

    @InjectMocks
    private AdminPartyroomAnalyticsQueryService service;

    private static final PartyroomId PID = new PartyroomId(100L);

    // ── getAnalytics: days 가드 ──

    @Test
    @DisplayName("getAnalytics - days=0 → BadRequestException, 존재확인 이전 검증")
    void analytics_days_too_small() {
        assertThatThrownBy(() -> service.getAnalytics(PID, 0))
                .isInstanceOf(BadRequestException.class);
        verify(aggregatePort, never()).findPartyroomById(any());
    }

    @Test
    @DisplayName("getAnalytics - days=91 → BadRequestException")
    void analytics_days_too_large() {
        assertThatThrownBy(() -> service.getAnalytics(PID, 91))
                .isInstanceOf(BadRequestException.class);
        verify(aggregatePort, never()).findPartyroomById(any());
    }

    @Test
    @DisplayName("getAnalytics - 방 없음 → PartyroomException(NOT_FOUND_ROOM)")
    void analytics_room_not_found() {
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAnalytics(PID, 7))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("파티룸을 찾을 수 없습니다");

        verify(activityRepo, never()).findDailyAttendance(any(), any(), any());
    }

    @Test
    @DisplayName("getAnalytics - happy path: 입퇴장 합산 + 무음지표 조합, totalExits == totalExited 불변식")
    void analytics_happy() {
        PartyroomData room = PartyroomData.create(
                "T", "i", com.pfplaybackend.api.party.domain.value.LinkDomain.of("l"),
                com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit.ofMinutes(5),
                com.pfplaybackend.api.party.domain.enums.StageType.GENERAL, new UserId(7L));
        when(aggregatePort.findPartyroomById(PID.getId())).thenReturn(Optional.of(room));

        List<DailyAttendanceBucket> daily = List.of(
                new DailyAttendanceBucket(LocalDate.of(2026, 7, 8), 3, 2),
                new DailyAttendanceBucket(LocalDate.of(2026, 7, 9), 1, 1));
        when(activityRepo.findDailyAttendance(eq(100L), any(), any())).thenReturn(daily);
        when(activityRepo.countUniqueVisitors(eq(100L), any(), any())).thenReturn(3L);

        // 3개의 EXIT 타임스탬프 → totalExits == totalExited == 3 이어야 함
        LocalDateTime base = LocalDateTime.now(SEOUL).minusHours(2);
        when(activityRepo.findExitOccurredAt(eq(100L), any(), any()))
                .thenReturn(List.of(base, base.plusMinutes(10), base.plusMinutes(20)));

        // playback 구간 하나 (endTime non-null)
        PlaybackData pb = PlaybackData.builder()
                .id(1L).partyroomId(PID).userId(new UserId(7L)).name("track")
                .endTime(base.plusMinutes(5).atZone(SEOUL).toInstant().toEpochMilli())
                .build();
        setField(pb, "createdAt", base);
        when(aggregatePort.findPlaybackForInterval(eq(PID), any(), any())).thenReturn(List.of(pb));

        PartyroomAnalyticsResponse resp = service.getAnalytics(PID, 7);

        assertThat(resp.windowDays()).isEqualTo(7);
        assertThat(resp.attendance().totalEntered()).isEqualTo(4L);
        assertThat(resp.attendance().totalExited()).isEqualTo(3L);
        assertThat(resp.attendance().uniqueVisitors()).isEqualTo(3L);
        assertThat(resp.attendance().daily()).hasSize(2);
        assertThat(resp.silenceExit().approximate()).isTrue();
        // 불변식: totalExits == attendance.totalExited
        assertThat(resp.silenceExit().totalExits()).isEqualTo(resp.attendance().totalExited());
        assertThat(resp.silenceExit().totalExits()).isEqualTo(3L);
    }

    // ── getDjHistory ──

    @Test
    @DisplayName("getDjHistory - 필드 매핑 + 닉네임/아바타 enrichment")
    void djHistory_enriches() {
        when(aggregatePort.findPartyroomById(PID.getId()))
                .thenReturn(Optional.of(PartyroomData.create(
                        "T", "i", com.pfplaybackend.api.party.domain.value.LinkDomain.of("l"),
                        com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit.ofMinutes(5),
                        com.pfplaybackend.api.party.domain.enums.StageType.GENERAL, new UserId(7L))));

        LocalDateTime playedAt = LocalDateTime.of(2026, 7, 9, 12, 0);
        PlaybackData pb = PlaybackData.builder()
                .id(42L).partyroomId(PID).userId(new UserId(11L))
                .name("Song A").thumbnailImage("thumb.png")
                .endTime(1L).build();
        setField(pb, "createdAt", playedAt);
        Pageable pageable = PageRequest.of(0, 20);
        when(aggregatePort.findPlaybackHistory(PID, pageable))
                .thenReturn(new PageImpl<>(List.of(pb), pageable, 1L));

        MemberData member = memberWithNickname(11L, "djNick");
        when(memberRepository.findAllByUserAccountIdIn(anyList())).thenReturn(List.of(member));

        Page<AdminDjHistoryItemResponse> page = service.getDjHistory(PID, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1L);
        AdminDjHistoryItemResponse item = page.getContent().get(0);
        assertThat(item.playbackId()).isEqualTo(42L);
        assertThat(item.trackName()).isEqualTo("Song A");
        assertThat(item.djUserAccountId()).isEqualTo(11L);
        assertThat(item.djNickname()).isEqualTo("djNick");
        assertThat(item.avatarIconUri()).isNotNull();
        assertThat(item.thumbnailImage()).isEqualTo("thumb.png");
        assertThat(item.playedAt()).isEqualTo(playedAt);
    }

    @Test
    @DisplayName("getDjHistory - 빈 페이지 → 예외 없이 빈 content, member 조회 skip")
    void djHistory_empty_page() {
        when(aggregatePort.findPartyroomById(PID.getId()))
                .thenReturn(Optional.of(PartyroomData.create(
                        "T", "i", com.pfplaybackend.api.party.domain.value.LinkDomain.of("l"),
                        com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit.ofMinutes(5),
                        com.pfplaybackend.api.party.domain.enums.StageType.GENERAL, new UserId(7L))));
        Pageable pageable = PageRequest.of(0, 20);
        when(aggregatePort.findPlaybackHistory(PID, pageable)).thenReturn(Page.empty(pageable));

        Page<AdminDjHistoryItemResponse> page = service.getDjHistory(PID, pageable);

        assertThat(page.getContent()).isEmpty();
        verify(memberRepository, never()).findAllByUserAccountIdIn(anyList());
    }

    // ── Helpers ──

    private static MemberData memberWithNickname(Long userAccountId, String nickname) {
        MemberData m = MemberData.createForUserAccount(userAccountId);
        ProfileData p = ProfileData.builder()
                .userId(new UserId(userAccountId))
                .build();
        p.updateBio(nickname, null);
        m.initializeProfile(p);
        return m;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Class<?> c = target.getClass();
            Field f = null;
            while (c != null) {
                try { f = c.getDeclaredField(fieldName); break; }
                catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
            }
            if (f == null) throw new NoSuchFieldException(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }
}
