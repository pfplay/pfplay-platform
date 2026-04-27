package com.pfplaybackend.api.administration.domain.enums;

public enum AdminActionTargetType {
    PARTYROOM,
    CREW                     // [PR 9] target_id = crew id, partyroom_id 컬럼 = 부모 룸 id
    // MEMBER는 PR 12에서 추가
}
