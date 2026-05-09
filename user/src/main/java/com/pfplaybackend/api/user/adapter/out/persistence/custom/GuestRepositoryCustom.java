package com.pfplaybackend.api.user.adapter.out.persistence.custom;

import com.pfplaybackend.api.user.domain.entity.data.GuestData;

import java.util.Optional;

public interface GuestRepositoryCustom {
    Optional<GuestData> findByUserAccountId(Long userAccountId);
}
