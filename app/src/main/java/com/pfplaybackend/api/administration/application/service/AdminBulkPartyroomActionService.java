package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.payload.request.BulkPartyroomActionRequest;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.BulkPartyroomActionResponse;
import com.pfplaybackend.api.common.exception.http.AbstractHTTPException;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * B-8 일괄 액션 outer 서비스 — 의도적으로 non-transactional.
 *
 * <p>per-item TX 경계는 {@link AdminPartyroomTransactionalUnit#executeOne}이 소유한다.
 * 외부 서비스에 @Transactional을 붙이면 항목별 commit/rollback이 불가능해진다 (전체가 한 TX).
 *
 * <p>{@code skipErrors=true}일 때 한 항목이 실패해도 나머지를 진행, false면 첫 실패에서 break.
 * 성공한 항목은 이미 자기 TX로 commit된 상태이므로 후속 break/실패에 영향받지 않는다.
 *
 * <p>per-item audit row는 각 항목 TX 안에서 listener가 INSERT 하므로 audit gap이 발생하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminBulkPartyroomActionService {

    private final AdminPartyroomTransactionalUnit txUnit;

    public BulkPartyroomActionResponse execute(BulkPartyroomActionRequest req, Long administratorId) {
        boolean skipErrors = req.skipErrorsOrDefault();
        List<BulkPartyroomActionResponse.BulkActionResult> results = new ArrayList<>();
        for (Long pid : req.partyroomIds()) {
            try {
                txUnit.executeOne(new PartyroomId(pid), req.action(), req.reason(), administratorId);
                results.add(new BulkPartyroomActionResponse.BulkActionResult(pid, true, null, null));
            } catch (Exception e) {
                String errMsg;
                String errCode;
                if (e instanceof AbstractHTTPException he) {
                    errMsg = he.getMessage();
                    errCode = he.getErrorCode();  // 14c §7.1 매트릭스 (PRT-001 등)
                } else {
                    errMsg = "INTERNAL_ERROR";
                    errCode = null;
                }
                results.add(new BulkPartyroomActionResponse.BulkActionResult(pid, false, errMsg, errCode));
                log.warn("[bulk-action] failed partyroomId={}, action={}, errorCode={}, error={}",
                        pid, req.action(), errCode, errMsg);
                if (!skipErrors) break;
            }
        }
        return new BulkPartyroomActionResponse(results);
    }
}
