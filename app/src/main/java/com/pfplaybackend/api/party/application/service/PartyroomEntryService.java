package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService.ConcurrentCrewInsertException;
import com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService.TryEnterResult;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 파티룸 입장 진입점. 같은 user · 같은 방 동시 입장에서 crew INSERT 에 진 요청을 새 트랜잭션으로 한 번
 * 재시도해 정상 응답으로 돌려준다 (#389).
 *
 * <p>재시도는 트랜잭션 밖에서만 의미가 있다. 진 트랜잭션은 rollback-only 이고 실패한 INSERT 의 잠금을
 * 쥐고 있으며 스냅샷에 승자 행도 보이지 않는다. 그래서 {@link PartyroomAccessCommandService#tryEnter} 의
 * 트랜잭션이 롤백으로 끝난 뒤 프록시를 거쳐 다시 호출한다. 재시도는 승자 행이 보이므로 같은 방 재입장
 * 분기로 정상 반환하고, ENTER 이벤트는 승자가 이미 발행했으므로 다시 발행하지 않는다.
 *
 * <p>주변 트랜잭션 안에서 호출되면 재시도도 같은 트랜잭션에 합류해 소용이 없으므로 재시도하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyroomEntryService {

    private final PartyroomAccessCommandService accessCommandService;

    public TryEnterResult tryEnter(PartyroomId partyroomId, CountryCode countryCode) {
        try {
            return accessCommandService.tryEnter(partyroomId, countryCode);
        } catch (ConcurrentCrewInsertException e) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw e;
            }
            log.info("[tryEnter] RETRY after concurrent crew insert - partyroomId={}", partyroomId.getId());
            return accessCommandService.tryEnter(partyroomId, countryCode);
        }
    }
}
