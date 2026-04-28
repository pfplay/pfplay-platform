package com.pfplaybackend.api.administration.domain.enums;

public enum AdminActionTargetType {
    PARTYROOM,
    CREW,                    // [PR 9] target_id = crew id, partyroom_id 컬럼 = 부모 룸 id
    AVATAR_BODY,             // [PR 11] target_id = avatar_body_resource.id, partyroom_id=null
    AVATAR_FACE              // [PR 11] target_id = avatar_face_resource.id, partyroom_id=null
    // MEMBER는 PR 12에서 추가
}
