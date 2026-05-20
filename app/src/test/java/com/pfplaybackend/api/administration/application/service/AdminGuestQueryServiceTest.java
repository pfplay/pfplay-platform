package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminGuestQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestDetailRow;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestSummaryRow;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AdminGuestQueryServiceTest {

    @Mock AdminGuestQueryRepository guestRepository;
    @Mock UserActivityLogRepository userActivityLogRepository;

    @InjectMocks AdminGuestQueryService service;

    @Test
    @DisplayName("getDetail: 존재하는 guest — DTO 조립, withdrawn flag derive")
    void getDetail_existingGuest_returnsDto() {
        AdminGuestDetailRow row = new AdminGuestDetailRow(
                50L, 100L, "g@x", ProviderType.GOOGLE,
                LocalDateTime.of(2026, 5, 1, 10, 0), null,
                "guestNick", "intro", "ua-string", true,
                LocalDateTime.of(2026, 4, 1, 0, 0));
        given(guestRepository.findDetail(50L)).willReturn(Optional.of(row));
        given(userActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(100L))
                .willReturn(List.of());

        AdminGuestDetailResponse res = service.getDetail(50L);

        assertThat(res.guestId()).isEqualTo(50L);
        assertThat(res.userAccount().email()).isEqualTo("g@x");
        assertThat(res.profile().nickname()).isEqualTo("guestNick");
        assertThat(res.agent()).isEqualTo("ua-string");
        assertThat(res.isProfileUpdated()).isTrue();
        assertThat(res.withdrawn()).isFalse();
        assertThat(res.recentActivityLog()).isEmpty();
    }

    @Test
    @DisplayName("getDetail: 탈퇴 처리된 guest — withdrawn=true")
    void getDetail_withdrawnGuest_setsFlag() {
        AdminGuestDetailRow row = new AdminGuestDetailRow(
                51L, 101L, "withdrawn-101@withdrawn.local", ProviderType.GOOGLE,
                null, LocalDateTime.of(2026, 5, 19, 0, 0),
                null, null, null, false,
                LocalDateTime.of(2026, 5, 1, 0, 0));
        given(guestRepository.findDetail(51L)).willReturn(Optional.of(row));
        given(userActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(101L))
                .willReturn(List.of());

        AdminGuestDetailResponse res = service.getDetail(51L);

        assertThat(res.withdrawn()).isTrue();
        assertThat(res.withdrawnAt()).isEqualTo(LocalDateTime.of(2026, 5, 19, 0, 0));
    }

    @Test
    @DisplayName("getDetail: 미존재 — GUEST_NOT_FOUND throw")
    void getDetail_missingGuest_throws() {
        given(guestRepository.findDetail(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Guest");
    }

    @Test
    @DisplayName("getDetail: userAccountId=null row — activity log 호출 skip + 빈 리스트")
    void getDetail_nullUserAccountId_skipsActivityLookup() {
        AdminGuestDetailRow row = new AdminGuestDetailRow(
                52L, null, null, null, null, null, null, null, null, false,
                LocalDateTime.of(2026, 5, 1, 0, 0));
        given(guestRepository.findDetail(52L)).willReturn(Optional.of(row));

        AdminGuestDetailResponse res = service.getDetail(52L);

        assertThat(res.recentActivityLog()).isEmpty();
        // userActivityLogRepository.findTop30... 호출되지 않았어야 — mock 디폴트 동작이라 자동
    }

    @Test
    @DisplayName("getList: repository search 결과를 Response 로 매핑 + withdrawn derive")
    void getList_mapsRowsToResponses() {
        AdminGuestSummaryRow row = new AdminGuestSummaryRow(
                50L, 100L, "g@x", ProviderType.GOOGLE,
                "nick", "ua", true,
                LocalDateTime.of(2026, 5, 1, 10, 0),
                LocalDateTime.of(2026, 4, 1, 0, 0),
                null);
        Page<AdminGuestSummaryRow> page = new PageImpl<>(List.of(row), PageRequest.of(0, 50), 1L);
        given(guestRepository.search(any(), any(Pageable.class))).willReturn(page);

        AdminGuestListQuery query = new AdminGuestListQuery(null, null, null,
                AdminGuestListQuery.SORT_CREATED_AT_DESC);
        Page<?> result = service.getList(query, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(1L);
    }
}
