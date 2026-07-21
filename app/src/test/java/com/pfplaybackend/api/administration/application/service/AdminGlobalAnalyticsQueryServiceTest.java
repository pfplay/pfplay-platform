package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminAttendanceAnalyticsResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminGlobalAnalyticsRepository;
import com.pfplaybackend.api.administration.application.dto.DailyAttendanceBucket;
import com.pfplaybackend.api.administration.application.dto.DailyUniqueVisitorsBucket;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

/** #361 전역 분석 서비스 단위 — days 검증·일자 병합·exitRecordRate. */
@ExtendWith(MockitoExtension.class)
class AdminGlobalAnalyticsQueryServiceTest {

    @Mock private AdminGlobalAnalyticsRepository repository;
    @InjectMocks private AdminGlobalAnalyticsQueryService service;

    private static final LocalDate D1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 2);

    @Test
    @DisplayName("days 범위 밖(0, 91) → ADM-GA-001 BadRequest")
    void days_out_of_range_rejected() {
        assertThatThrownBy(() -> service.getAttendance(0, true))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.getAttendance(91, true))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("일자 병합 — attendance·unique 를 날짜로 병합, 한쪽에만 있는 날짜는 0 채움")
    void merges_daily_rows_by_date() {
        when(repository.findDailyAttendance(any(), any(), anyBoolean()))
                .thenReturn(List.of(new DailyAttendanceBucket(D1, 5, 4)));
        when(repository.findDailyUniqueVisitors(any(), any(), anyBoolean()))
                .thenReturn(List.of(
                        new DailyUniqueVisitorsBucket(D1, 3),
                        new DailyUniqueVisitorsBucket(D2, 1))); // attendance 없는 날짜
        when(repository.countUniqueVisitors(any(), any(), anyBoolean())).thenReturn(4L);
        when(repository.countActiveRooms()).thenReturn(2L);

        AdminAttendanceAnalyticsResponse res = service.getAttendance(7, true);

        assertThat(res.daily()).hasSize(2);
        assertThat(res.daily().get(0).date()).isEqualTo(D1);
        assertThat(res.daily().get(0).entered()).isEqualTo(5);
        assertThat(res.daily().get(0).uniqueVisitors()).isEqualTo(3);
        assertThat(res.daily().get(1).date()).isEqualTo(D2);
        assertThat(res.daily().get(1).entered()).isZero();
        assertThat(res.daily().get(1).uniqueVisitors()).isEqualTo(1);
        assertThat(res.summary().totalEntered()).isEqualTo(5);
        assertThat(res.summary().totalExited()).isEqualTo(4);
        assertThat(res.summary().uniqueVisitors()).isEqualTo(4);
        assertThat(res.summary().activeRoomCount()).isEqualTo(2);
        assertThat(res.summary().exitRecordRate()).isCloseTo(0.8, offset(1e-9));
    }

    @Test
    @DisplayName("입장 0건 → exitRecordRate=null (0 나누기 방지)")
    void exit_record_rate_null_when_no_enters() {
        when(repository.findDailyAttendance(any(), any(), anyBoolean())).thenReturn(List.of());
        when(repository.findDailyUniqueVisitors(any(), any(), anyBoolean())).thenReturn(List.of());
        when(repository.countUniqueVisitors(any(), any(), anyBoolean())).thenReturn(0L);
        when(repository.countActiveRooms()).thenReturn(0L);

        AdminAttendanceAnalyticsResponse res = service.getAttendance(7, true);

        assertThat(res.summary().exitRecordRate()).isNull();
        assertThat(res.daily()).isEmpty();
    }
}
