package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminDjHistoryItemResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.PartyroomAnalyticsResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogAnalyticsRepository;
import com.pfplaybackend.api.administration.application.analytics.PlaybackInterval;
import com.pfplaybackend.api.administration.application.analytics.SilenceExitCalculator;
import com.pfplaybackend.api.administration.application.dto.AttendanceAnalytics;
import com.pfplaybackend.api.administration.application.dto.DailyAttendanceBucket;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * B-3/B-4 룸 행동분석 조합 서비스.
 *
 * <p>② 입퇴장 집계({@link UserActivityLogAnalyticsRepository}), ③ 무음 이탈 근사
 * ({@link SilenceExitCalculator}), ④ 디제잉 이력(닉네임/아바타 enrichment)을 조합한다.
 * 모든 시각 계산은 Asia/Seoul 벽시계 기준 — DB occurred_at/created_at 이 KST 로컬시각이므로
 * epoch millis 변환도 동일 존을 사용한다.
 *
 * <p>Cross-BC 읽기는 {@link PartyroomAggregatePort}(Party)/{@link MemberRepository}(User)를 통해서만
 * 이뤄지며, Administration BC 는 Party/User 엔티티 타입을 직접 다루지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPartyroomAnalyticsQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    private final PartyroomAggregatePort aggregatePort;
    private final UserActivityLogAnalyticsRepository activityRepo;
    private final MemberRepository memberRepository;

    public PartyroomAnalyticsResponse getAnalytics(PartyroomId partyroomId, int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BadRequestException("ADM-PR-002", "days out of range (1..90): " + days);
        }
        requireRoom(partyroomId);

        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDateTime from = now.minusDays(days);
        long pid = partyroomId.getId();

        List<DailyAttendanceBucket> daily = activityRepo.findDailyAttendance(pid, from, now);
        long totalEntered = daily.stream().mapToLong(DailyAttendanceBucket::entered).sum();
        long totalExited = daily.stream().mapToLong(DailyAttendanceBucket::exited).sum();
        long unique = activityRepo.countUniqueVisitors(pid, from, now);
        AttendanceAnalytics attendance = new AttendanceAnalytics(totalEntered, totalExited, unique, daily);

        long fromMs = ms(from);
        long nowMs = ms(now);
        List<PlaybackInterval> intervals = aggregatePort.findPlaybackForInterval(partyroomId, from, now).stream()
                .filter(p -> p.getEndTime() != null)
                .map(p -> new PlaybackInterval(ms(p.getCreatedAt()), p.getEndTime()))
                .toList();
        List<Long> exits = activityRepo.findExitOccurredAt(pid, from, now).stream()
                .map(this::ms)
                .toList();
        SilenceExitCalculator.Result s = SilenceExitCalculator.compute(intervals, exits, fromMs, nowMs);
        PartyroomAnalyticsResponse.SilenceExit silence = new PartyroomAnalyticsResponse.SilenceExit(
                true, s.totalExits(), s.exitsDuringSilence(), s.silenceExitRatio(), s.totalSilenceMinutes());

        return new PartyroomAnalyticsResponse(days, attendance, silence);
    }

    public Page<AdminDjHistoryItemResponse> getDjHistory(PartyroomId partyroomId, Pageable pageable) {
        requireRoom(partyroomId);
        Page<PlaybackData> page = aggregatePort.findPlaybackHistory(partyroomId, pageable);
        List<Long> userIds = page.getContent().stream()
                .map(p -> p.getUserId().getUid())
                .distinct()
                .toList();
        Map<Long, MemberData> byId = userIds.isEmpty()
                ? Map.of()
                : memberRepository.findAllByUserAccountIdIn(userIds).stream()
                        .collect(Collectors.toMap(MemberData::getUserAccountId, m -> m, (a, b) -> a));
        return page.map(p -> {
            MemberData m = byId.get(p.getUserId().getUid());
            return new AdminDjHistoryItemResponse(
                    p.getId(),
                    p.getName(),
                    p.getUserId().getUid(),
                    nickname(m),
                    avatarUri(m),
                    p.getThumbnailImage(),
                    p.getCreatedAt());
        });
    }

    private void requireRoom(PartyroomId id) {
        aggregatePort.findPartyroomById(id.getId())
                .orElseThrow(() -> ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM));
    }

    private long ms(LocalDateTime t) {
        return t.atZone(SEOUL).toInstant().toEpochMilli();
    }

    private static String nickname(MemberData m) {
        return (m == null || m.getProfileData() == null) ? null : m.getProfileData().getNicknameValue();
    }

    private static String avatarUri(MemberData m) {
        if (m == null || m.getProfileData() == null || m.getProfileData().getAvatarSetting() == null) {
            return null;
        }
        return m.getProfileData().getAvatarSetting().getAvatarIconUriValue();
    }
}
