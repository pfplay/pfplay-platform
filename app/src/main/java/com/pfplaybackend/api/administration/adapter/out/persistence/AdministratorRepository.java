package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdministratorRepository extends JpaRepository<AdministratorData, Long> {
    Optional<AdministratorData> findByUserAccountId(Long userAccountId);
    Optional<AdministratorData> findFirstByRoleAndRevokedAtIsNull(AdminRole role);
    boolean existsByUserAccountId(Long userAccountId);

    List<AdministratorData> findAllByOrderByGrantedAtDesc();

    long countByRoleAndRevokedAtIsNull(AdminRole role);
}
