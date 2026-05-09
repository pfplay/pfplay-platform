package com.pfplaybackend.api.party.domain.specification;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;
import com.pfplaybackend.api.party.domain.exception.PenaltyException;

import java.util.Optional;

public class PartyroomEntrySpecification {

    public void validate(PartyroomData partyroom, long activeCrewCount, Optional<CrewData> existingCrew) {
        partyroom.validateNotTerminated();
        if (partyroom.isSuspended()) {
            // SUSPENDED 룸 입장 거부 — PR 8에서 어드민이 룸을 정지시킨 경우.
            // PR 7 시점엔 SUSPENDED 진입 경로 없지만 픽스처로 SUSPENDED 룸 만들어 가드 검증 가능.
            throw ExceptionCreator.create(PartyroomException.ILLEGAL_STATE_TRANSITION);
        }
        if (activeCrewCount > 49) throw ExceptionCreator.create(PartyroomException.EXCEEDED_LIMIT);
        existingCrew.filter(CrewData::isBanned).ifPresent(c -> {
            throw ExceptionCreator.create(PenaltyException.PERMANENT_EXPULSION);
        });
    }
}
