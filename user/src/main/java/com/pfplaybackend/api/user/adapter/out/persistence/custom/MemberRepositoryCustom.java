package com.pfplaybackend.api.user.adapter.out.persistence.custom;

import com.pfplaybackend.api.user.domain.entity.data.MemberData;

import java.util.Optional;

public interface MemberRepositoryCustom {
    Optional<MemberData> findByUserAccountId(Long userAccountId);
}
