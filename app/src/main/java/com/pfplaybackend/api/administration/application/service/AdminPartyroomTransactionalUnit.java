package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.domain.enums.BulkActionType;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 별도의 @Service bean으로 분리된 transactional unit.
 *
 * <p>{@link AdminBulkPartyroomActionService}가 동일 클래스에서 @Transactional 메서드를
 * self-invoke 하면 Spring AOP proxy가 우회되어 새 TX가 시작되지 않는다. 이를 회피하기
 * 위해 per-item TX 경계를 갖는 메서드를 다른 bean으로 옮긴다 (PR 7 sample 패턴).
 *
 * <p>한 항목의 실패는 그 항목의 TX만 롤백한다 — 이미 commit된 항목들은 영향을 받지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminPartyroomTransactionalUnit {

    private final AdminPartyroomCommandService commandService;

    @Transactional
    public void executeOne(PartyroomId partyroomId, BulkActionType action, String reason, Long administratorId) {
        switch (action) {
            case TERMINATE  -> commandService.terminate(partyroomId, reason, administratorId);
            case SUSPEND    -> commandService.suspend(partyroomId, reason, administratorId);
            case SET_HIDDEN -> commandService.setDisplayFlag(partyroomId, DisplayFlag.HIDDEN, administratorId);
        }
    }
}
