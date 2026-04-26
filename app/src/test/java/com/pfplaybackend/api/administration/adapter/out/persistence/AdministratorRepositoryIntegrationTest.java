package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class AdministratorRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AdministratorRepository administratorRepository;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Test
    void findAllByOrderByGrantedAtDesc_returnsRowsNewestFirst() {
        // Seed three user_account rows and three administrator rows.
        // createForLocalWithMandatoryChange stamps grantedAt = now() at save time;
        // we rely on save order for DESC ordering (a3 saved last → newest).
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(100L), "super@x", "h0"));
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(101L), "a1@x", "h1"));
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(102L), "a2@x", "h2"));

        AdministratorData superAdmin = administratorRepository.save(
                AdministratorData.createSuperAdmin(100L));
        AdministratorData a1 = administratorRepository.save(
                AdministratorData.createAdmin(101L, superAdmin.getAdministratorId()));
        AdministratorData a2 = administratorRepository.save(
                AdministratorData.createAdmin(102L, superAdmin.getAdministratorId()));

        flushAndClear();

        List<AdministratorData> rows = administratorRepository.findAllByOrderByGrantedAtDesc();
        assertThat(rows).hasSize(3);
        // a2 saved last → newest grantedAt → first in DESC order; superAdmin is oldest.
        assertThat(rows.stream().map(AdministratorData::getAdministratorId))
                .startsWith(a2.getAdministratorId(), a1.getAdministratorId());
        assertThat(rows.get(rows.size() - 1).getAdministratorId())
                .isEqualTo(superAdmin.getAdministratorId());
    }

    @Test
    void countByRoleAndRevokedAtIsNull_excludesRevoked_andRespectsRoleFilter() {
        // Seed: 1 SUPER_ADMIN, 1 active ADMIN, 1 revoked ADMIN.
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(200L), "super@y", "h"));
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(201L), "active@y", "h"));
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(202L), "revoked@y", "h"));

        AdministratorData superAdmin = administratorRepository.save(
                AdministratorData.createSuperAdmin(200L));
        administratorRepository.save(
                AdministratorData.createAdmin(201L, superAdmin.getAdministratorId()));
        AdministratorData revoked = administratorRepository.save(
                AdministratorData.createAdmin(202L, superAdmin.getAdministratorId()));
        revoked.revoke();

        flushAndClear();

        assertThat(administratorRepository.countByRoleAndRevokedAtIsNull(AdminRole.SUPER_ADMIN))
                .isEqualTo(1L);
        assertThat(administratorRepository.countByRoleAndRevokedAtIsNull(AdminRole.ADMIN))
                .isEqualTo(1L);  // only the active one; revoked is excluded
    }
}
