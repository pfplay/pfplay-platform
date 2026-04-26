package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.administration.domain.value.AdminRole;
import com.pfplaybackend.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Entity
@Table(name = "administrator")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DynamicInsert
@DynamicUpdate
public class AdministratorData extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "administrator_id")
    private Long administratorId;

    @Column(name = "user_account_id", nullable = false)
    private Long userAccountId;

    @Column(name = "role", nullable = false, length = 32, columnDefinition = "VARCHAR(32)")
    @Enumerated(EnumType.STRING)
    private AdminRole role;

    @Column(name = "granted_by_administrator_id")
    private Long grantedByAdministratorId;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private AdministratorData(Long userAccountId, AdminRole role,
                              Long grantedByAdministratorId, LocalDateTime grantedAt) {
        this.userAccountId = userAccountId;
        this.role = role;
        this.grantedByAdministratorId = grantedByAdministratorId;
        this.grantedAt = grantedAt;
    }

    public static AdministratorData createSuperAdmin(Long userAccountId) {
        return AdministratorData.builder()
            .userAccountId(userAccountId)
            .role(AdminRole.SUPER_ADMIN)
            .grantedByAdministratorId(null)
            .grantedAt(LocalDateTime.now())
            .build();
    }

    public static AdministratorData createAdmin(Long userAccountId, Long grantedByAdministratorId) {
        return AdministratorData.builder()
            .userAccountId(userAccountId)
            .role(AdminRole.ADMIN)
            .grantedByAdministratorId(grantedByAdministratorId)
            .grantedAt(LocalDateTime.now())
            .build();
    }

    public void revoke() {
        if (revokedAt != null) {
            return; // idempotent
        }
        this.revokedAt = LocalDateTime.now();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
