package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService.ConcurrentCrewInsertException;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService.TryEnterResult;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.exception.CrewException;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartyroomEntryServiceTest {

    @Mock private PartyroomAccessCommandService accessCommandService;

    @InjectMocks
    private PartyroomEntryService entryService;

    private final PartyroomId partyroomId = new PartyroomId(1L);
    private final CountryCode countryCode = CountryCode.of("KR");

    private static ConcurrentCrewInsertException insertLost() {
        return new ConcurrentCrewInsertException(new DataIntegrityViolationException("uk_crew_partyroom_user"));
    }

    @Test
    @DisplayName("INSERT 패배 → 한 번 재시도한 결과를 그대로 반환")
    void retries_once_after_concurrent_insert() {
        TryEnterResult winner = new TryEnterResult(mock(CrewData.class), false);
        when(accessCommandService.tryEnter(partyroomId, countryCode))
                .thenThrow(insertLost())
                .thenReturn(winner);

        assertThat(entryService.tryEnter(partyroomId, countryCode)).isSameAs(winner);
        verify(accessCommandService, times(2)).tryEnter(partyroomId, countryCode);
    }

    @Test
    @DisplayName("재시도도 INSERT 에 지면 예외를 그대로 올린다 (재시도는 1회)")
    void retries_only_once() {
        when(accessCommandService.tryEnter(partyroomId, countryCode))
                .thenThrow(insertLost())
                .thenThrow(insertLost());

        assertThatThrownBy(() -> entryService.tryEnter(partyroomId, countryCode))
                .isInstanceOf(ConcurrentCrewInsertException.class);
        verify(accessCommandService, times(2)).tryEnter(partyroomId, countryCode);
    }

    @Test
    @DisplayName("다른 방 동시 입장(CONCURRENT_ACTIVE_ROOM) 등 다른 예외는 재시도하지 않는다")
    void does_not_retry_other_failures() {
        RuntimeException conflict = ExceptionCreator.create(CrewException.CONCURRENT_ACTIVE_ROOM);
        when(accessCommandService.tryEnter(partyroomId, countryCode)).thenThrow(conflict);

        assertThatThrownBy(() -> entryService.tryEnter(partyroomId, countryCode)).isSameAs(conflict);
        verify(accessCommandService, times(1)).tryEnter(partyroomId, countryCode);
    }

    @Test
    @DisplayName("주변 트랜잭션 안이면 재시도가 같은 트랜잭션에 합류하므로 재시도하지 않는다")
    void does_not_retry_inside_surrounding_transaction() {
        when(accessCommandService.tryEnter(partyroomId, countryCode)).thenThrow(insertLost());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> entryService.tryEnter(partyroomId, countryCode))
                    .isInstanceOf(ConcurrentCrewInsertException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(accessCommandService, times(1)).tryEnter(partyroomId, countryCode);
    }
}
