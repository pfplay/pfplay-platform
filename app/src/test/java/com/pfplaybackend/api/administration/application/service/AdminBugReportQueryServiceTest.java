package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminBugReportListResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminBugReportQueryRepository;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBugReportQueryServiceTest {

    @Mock AdminBugReportQueryRepository repository;
    @InjectMocks AdminBugReportQueryService service;

    @Test
    @DisplayName("getList — 정상 paging + count")
    void getListHappy() {
        AdminBugReportSummaryDto row = new AdminBugReportSummaryDto(
                1L, 100L, "user@x.com", null, "preview", 7L,
                LocalDateTime.of(2026, 5, 21, 10, 0));
        when(repository.findRows(any())).thenReturn(List.of(row));
        when(repository.count(any())).thenReturn(1L);

        AdminBugReportListResponse result = service.getList(AdminBugReportListQuery.builder()
                .page(0).size(20).sortBy("createdAt").direction("DESC").build());

        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.totalPages()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).bugReportId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getList — sortBy 미지원이면 BUG-002")
    void getListInvalidSortByThrows() {
        assertThatThrownBy(() -> service.getList(AdminBugReportListQuery.builder()
                        .page(0).size(20).sortBy("badField").direction("DESC").build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("getList — direction 미지원이면 BUG-002")
    void getListInvalidDirectionThrows() {
        assertThatThrownBy(() -> service.getList(AdminBugReportListQuery.builder()
                        .page(0).size(20).sortBy("createdAt").direction("RANDOM").build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("getDetail — 정상")
    void getDetailHappy() {
        AdminBugReportDetailDto detail = new AdminBugReportDetailDto(
                1L, 100L, "user@x.com", null, "content body",
                "https://pfplay.xyz/parties/7", "Mozilla/5.0", 7L, "테스트 룸",
                LocalDateTime.of(2026, 5, 21, 10, 0));
        when(repository.findDetail(1L)).thenReturn(Optional.of(detail));

        AdminBugReportDetailDto result = service.getDetail(1L);

        assertThat(result.content()).isEqualTo("content body");
    }

    @Test
    @DisplayName("getDetail — 없으면 BUG-003 NotFoundException")
    void getDetailNotFoundThrows() {
        when(repository.findDetail(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(999L))
                .isInstanceOf(NotFoundException.class);
    }
}
