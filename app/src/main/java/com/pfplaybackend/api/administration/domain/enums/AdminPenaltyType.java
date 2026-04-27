package com.pfplaybackend.api.administration.domain.enums;

import com.pfplaybackend.api.party.domain.enums.PenaltyType;

/**
 * 어드민이 부과 가능한 페널티 종류 (Q2=B에서 expulsion 2종으로 한정).
 * party의 PenaltyType을 그대로 노출하지 않고 ACL로 둠.
 */
public enum AdminPenaltyType {
    ONE_TIME_EXPULSION(PenaltyType.ONE_TIME_EXPULSION),
    PERMANENT_EXPULSION(PenaltyType.PERMANENT_EXPULSION);

    private final PenaltyType partyEnum;

    AdminPenaltyType(PenaltyType partyEnum) {
        this.partyEnum = partyEnum;
    }

    public PenaltyType toPartyEnum() {
        return partyEnum;
    }
}
