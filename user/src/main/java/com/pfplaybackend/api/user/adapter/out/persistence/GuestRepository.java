package com.pfplaybackend.api.user.adapter.out.persistence;

import com.pfplaybackend.api.user.adapter.out.persistence.custom.GuestRepositoryCustom;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GuestRepository extends JpaRepository<GuestData, Long>, GuestRepositoryCustom {

    Optional<GuestData> findByUserAccountId(Long userAccountId);
}
