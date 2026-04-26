package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.value.AdminRole;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AdministratorDataTest {

    @Test
    void createSuperAdmin_setsRoleAndUserAccountId() {
        var admin = AdministratorData.createSuperAdmin(1L);

        assertThat(admin.getUserAccountId()).isEqualTo(1L);
        assertThat(admin.getRole()).isEqualTo(AdminRole.SUPER_ADMIN);
        assertThat(admin.getGrantedByAdministratorId()).isNull();
        assertThat(admin.getRevokedAt()).isNull();
        assertThat(admin.getAdministratorId()).isNull(); // assigned on persist
    }

    @Test
    void createAdmin_recordsGrantedBy() {
        var admin = AdministratorData.createAdmin(2L, 1L);

        assertThat(admin.getRole()).isEqualTo(AdminRole.ADMIN);
        assertThat(admin.getGrantedByAdministratorId()).isEqualTo(1L);
    }

    @Test
    void revoke_setsRevokedAt() {
        var admin = AdministratorData.createAdmin(2L, 1L);
        assertThat(admin.isRevoked()).isFalse();

        admin.revoke();

        assertThat(admin.isRevoked()).isTrue();
        assertThat(admin.getRevokedAt()).isNotNull();
    }

    @Test
    void revoke_isIdempotent() {
        var admin = AdministratorData.createAdmin(2L, 1L);
        admin.revoke();
        var first = admin.getRevokedAt();

        admin.revoke(); // second call should not change revokedAt

        assertThat(admin.getRevokedAt()).isEqualTo(first);
    }
}
