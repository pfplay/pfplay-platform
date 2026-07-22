package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminAttendanceAnalyticsResponse;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminAttendanceAnalyticsResponse.Daily;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminAttendanceAnalyticsResponse.Summary;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminGlobalAnalyticsRepository;
import com.pfplaybackend.api.administration.application.dto.DailyAttendanceBucket;
import com.pfplaybackend.api.administration.application.dto.DailyUniqueVisitorsBucket;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * #361 어드민 대시보드 v1 — 전역 입퇴장 분석 조합 서비스.
 *
 * <p>방 단위 {@link AdminPartyroomAnalyticsQueryService} 와 동일한 시각 규약(Asia/Seoul 벽시계,
 * days 1..90)을 따른다. 일별 attendance 와 일별 unique 는 별도 그룹 쿼리로 얻어 날짜 기준으로
 * 병합한다(둘 중 한쪽에만 존재하는 날짜도 노출 — 값 0 채움).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminGlobalAnalyticsQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    private final AdminGlobalAnalyticsRepository repository;

    public AdminAttendanceAnalyticsResponse getAttendance(int days, boolean excludeBots) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BadRequestException("ADM-GA-001", "days out of range (1..90): " + days);
        }
        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDateTime from = now.minusDays(days);

        List<DailyAttendanceBucket> attendance = repository.findDailyAttendance(from, now, excludeBots);
        Map<LocalDate, DailyUniqueVisitorsBucket> uniqueByDate =
                repository.findDailyUniqueVisitors(from, now, excludeBots).stream()
                        .collect(Collectors.toMap(DailyUniqueVisitorsBucket::date, Function.identity()));

        // 날짜 병합 (asc 정렬 보장) — attendance 없는 날짜에 unique 만 있는 경우도 보존
        Map<LocalDate, Daily> merged = new TreeMap<>();
        for (DailyAttendanceBucket b : attendance) {
            DailyUniqueVisitorsBucket u = uniqueByDate.get(b.date());
            merged.put(b.date(), new Daily(b.date(), b.entered(), b.exited(), u == null ? 0L : u.uniqueVisitors()));
        }
        for (DailyUniqueVisitorsBucket u : uniqueByDate.values()) {
            merged.putIfAbsent(u.date(), new Daily(u.date(), 0L, 0L, u.uniqueVisitors()));
        }

        long totalEntered = attendance.stream().mapToLong(DailyAttendanceBucket::entered).sum();
        long totalExited = attendance.stream().mapToLong(DailyAttendanceBucket::exited).sum();
        long uniqueVisitors = repository.countUniqueVisitors(from, now, excludeBots);
        long activeRoomCount = repository.countActiveRooms();
        Double exitRecordRate = totalEntered == 0 ? null : (double) totalExited / totalEntered;

        return new AdminAttendanceAnalyticsResponse(
                days, excludeBots,
                new Summary(totalEntered, totalExited, uniqueVisitors, activeRoomCount, exitRecordRate),
                List.copyOf(merged.values())
        );
    }
}
