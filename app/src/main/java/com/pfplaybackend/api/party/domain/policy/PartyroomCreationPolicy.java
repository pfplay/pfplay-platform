package com.pfplaybackend.api.party.domain.policy;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.party.domain.exception.PartyroomException;

public class PartyroomCreationPolicy {

    public void enforce(AuthorityTier authorityTier) {
        // Member 등급(FM, AM) 만 허용. GT(게스트)는 차단.
        // 파티룸 신고(PartyroomReportCommandService.guardMemberOnly) 와 동일한 허용선.
        if (authorityTier != AuthorityTier.FM && authorityTier != AuthorityTier.AM) {
            throw ExceptionCreator.create(PartyroomException.RESTRICTED_AUTHORITY);
        }
    }
}
