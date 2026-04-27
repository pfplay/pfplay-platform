package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.BulkPartyroomActionRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.BulkPartyroomActionResponse;
import com.pfplaybackend.api.administration.domain.enums.BulkActionType;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link AdminBulkPartyroomActionService}.
 *
 * <p>{@link AdminPartyroomTransactionalUnit#executeOne}을 mock 처리하고 outer 서비스의
 * 항목별 결과 누적/skipErrors 분기 동작만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminBulkPartyroomActionServiceTest {

    @Mock
    private AdminPartyroomTransactionalUnit txUnit;

    @InjectMocks
    private AdminBulkPartyroomActionService service;

    @Test
    @DisplayName("all-success — 3 ids, all success=true, txUnit 3회 호출")
    void allSuccess() {
        BulkPartyroomActionRequest req = new BulkPartyroomActionRequest(
                List.of(1L, 2L, 3L), BulkActionType.TERMINATE, "violation", null);
        willDoNothing().given(txUnit).executeOne(any(), any(), any(), any());

        BulkPartyroomActionResponse resp = service.execute(req, 99L);

        assertThat(resp.results()).hasSize(3);
        assertThat(resp.results()).allMatch(BulkPartyroomActionResponse.BulkActionResult::success);
        assertThat(resp.results())
                .extracting(BulkPartyroomActionResponse.BulkActionResult::partyroomId)
                .containsExactly(1L, 2L, 3L);
        verify(txUnit, times(3)).executeOne(any(), eq(BulkActionType.TERMINATE), eq("violation"), eq(99L));
    }

    @Test
    @DisplayName("skipErrors=true — 중간 항목 실패 시 나머지 진행, error 메시지 노출")
    void skipErrorsTrue_continuesOnFailure() {
        BulkPartyroomActionRequest req = new BulkPartyroomActionRequest(
                List.of(10L, 20L, 30L), BulkActionType.SUSPEND, "hold", true);

        willDoNothing().given(txUnit)
                .executeOne(eq(new PartyroomId(10L)), any(), any(), any());
        willThrow(ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION))
                .given(txUnit)
                .executeOne(eq(new PartyroomId(20L)), any(), any(), any());
        willDoNothing().given(txUnit)
                .executeOne(eq(new PartyroomId(30L)), any(), any(), any());

        BulkPartyroomActionResponse resp = service.execute(req, 7L);

        assertThat(resp.results()).hasSize(3);
        assertThat(resp.results().get(0).success()).isTrue();
        assertThat(resp.results().get(0).error()).isNull();

        assertThat(resp.results().get(1).success()).isFalse();
        assertThat(resp.results().get(1).partyroomId()).isEqualTo(20L);
        assertThat(resp.results().get(1).error()).isNotBlank();

        assertThat(resp.results().get(2).success()).isTrue();
        verify(txUnit, times(3)).executeOne(any(), any(), any(), any());
    }

    @Test
    @DisplayName("skipErrors=false — 첫 실패 시 break, 후속 ids는 호출되지 않음")
    void skipErrorsFalse_breaksOnFirstFailure() {
        BulkPartyroomActionRequest req = new BulkPartyroomActionRequest(
                List.of(10L, 20L, 30L), BulkActionType.TERMINATE, "abuse", false);

        willThrow(ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION))
                .given(txUnit)
                .executeOne(eq(new PartyroomId(10L)), any(), any(), any());

        BulkPartyroomActionResponse resp = service.execute(req, 1L);

        assertThat(resp.results()).hasSize(1);
        assertThat(resp.results().get(0).success()).isFalse();
        assertThat(resp.results().get(0).partyroomId()).isEqualTo(10L);
        assertThat(resp.results().get(0).error()).isNotBlank();

        verify(txUnit, times(1)).executeOne(any(), any(), any(), any());
        verify(txUnit, never()).executeOne(eq(new PartyroomId(20L)), any(), any(), any());
        verify(txUnit, never()).executeOne(eq(new PartyroomId(30L)), any(), any(), any());
    }

    @Test
    @DisplayName("non-HTTP exception — error message는 INTERNAL_ERROR로 normalize")
    void unexpectedException_normalizedToInternalError() {
        BulkPartyroomActionRequest req = new BulkPartyroomActionRequest(
                List.of(50L), BulkActionType.SET_HIDDEN, "moderate", true);
        willThrow(new RuntimeException("DB connection lost"))
                .given(txUnit).executeOne(any(), any(), any(), any());

        BulkPartyroomActionResponse resp = service.execute(req, 1L);

        assertThat(resp.results()).hasSize(1);
        assertThat(resp.results().get(0).success()).isFalse();
        assertThat(resp.results().get(0).error()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("skipErrors null → default true (계속 진행)")
    void skipErrorsNull_defaultsToTrue() {
        BulkPartyroomActionRequest req = new BulkPartyroomActionRequest(
                List.of(10L, 20L), BulkActionType.SET_HIDDEN, "policy", null);
        willThrow(ExceptionCreator.create(PartyroomException.NOT_FOUND_ROOM))
                .given(txUnit)
                .executeOne(eq(new PartyroomId(10L)), any(), any(), any());
        willDoNothing().given(txUnit)
                .executeOne(eq(new PartyroomId(20L)), any(), any(), any());

        BulkPartyroomActionResponse resp = service.execute(req, 1L);

        assertThat(resp.results()).hasSize(2);
        assertThat(resp.results().get(0).success()).isFalse();
        assertThat(resp.results().get(1).success()).isTrue();
    }
}
