package com.pfplaybackend.api.user.adapter.out.persistence;

import com.pfplaybackend.api.user.adapter.out.persistence.custom.GuestRepositoryCustom;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestRepository extends JpaRepository<GuestData, Long>, GuestRepositoryCustom {
}
