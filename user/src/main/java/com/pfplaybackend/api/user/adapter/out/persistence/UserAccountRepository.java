package com.pfplaybackend.api.user.adapter.out.persistence;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccountData, UserId> {

    Optional<UserAccountData> findByUserId(UserId userId);

    Optional<UserAccountData> findByEmail(String email);

    Optional<UserAccountData> findByEmailAndProviderType(String email, ProviderType providerType);

    boolean existsByEmail(String email);

    long countByProviderType(ProviderType providerType);

    List<UserAccountData> findAllByUserIdIn(Collection<UserId> userIds);
}
