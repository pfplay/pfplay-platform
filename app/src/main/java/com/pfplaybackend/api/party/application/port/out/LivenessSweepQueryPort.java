package com.pfplaybackend.api.party.application.port.out;

import com.pfplaybackend.api.party.domain.entity.data.CrewData;

import java.time.LocalDateTime;
import java.util.List;

/**
 * #356 presence liveness 스윕 후보 조회 포트.
 *
 * <p>봇 판별(user_account.is_dummy)이 필요해 cross-BC 조인을 수반하므로, party 모듈의
 * 합법적 통합 경계인 {@code adapter.out.external} 어댑터로 분리한다
 * (CrossContextDependencyTest — party 는 user.domain 직접 참조 금지).
 */
public interface LivenessSweepQueryPort {

    /**
     * 후보: {@code is_active=1 AND pending_exit_at IS NULL} 인 crew 중
     * <b>봇 제외</b>(is_dummy=false — 봇은 WS 세션이 없어 미제외 시 전 봇이 스윕 대상)
     * + <b>최근 입장 유예</b>({@code entered_at < enteredBefore} — WS 연결 수립 전 오탐 방지).
     * 세션 존재 여부(liveness) 판정은 호출자 몫.
     */
    List<CrewData> findLivenessSweepCandidates(LocalDateTime enteredBefore);
}
