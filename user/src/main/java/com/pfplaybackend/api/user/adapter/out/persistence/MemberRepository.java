package com.pfplaybackend.api.user.adapter.out.persistence;

import com.pfplaybackend.api.user.adapter.out.persistence.custom.MemberRepositoryCustom;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<MemberData, Long>, MemberRepositoryCustom {

    boolean existsByUserAccountId(Long userAccountId);

    Optional<MemberData> findByUserAccountId(Long userAccountId);
}
