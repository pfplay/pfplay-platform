package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.entity.BaseEntity;
import com.pfplaybackend.api.user.domain.event.UserAccountWithdrawnEvent;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DynamicInsert
@DynamicUpdate
public class UserAccountData extends BaseEntity {

    @EmbeddedId
    @AttributeOverride(name = "uid", column = @Column(name = "user_id"))
    private UserId userId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "provider_type", nullable = false, length = 16, columnDefinition = "VARCHAR(16)")
    @Enumerated(EnumType.STRING)
    private ProviderType providerType;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Builder(access = AccessLevel.PRIVATE)
    private UserAccountData(UserId userId, String email, ProviderType providerType,
                            String passwordHash, LocalDateTime lastLoginAt,
                            LocalDateTime withdrawnAt, boolean mustChangePassword) {
        this.userId = userId;
        this.email = email;
        this.providerType = providerType;
        this.passwordHash = passwordHash;
        this.lastLoginAt = lastLoginAt;
        this.withdrawnAt = withdrawnAt;
        this.mustChangePassword = mustChangePassword;
    }

    public static UserAccountData createForSocial(UserId userId, String email, ProviderType providerType) {
        Objects.requireNonNull(providerType, "providerType must not be null");
        if (providerType == ProviderType.LOCAL) {
            throw new IllegalArgumentException("Use createForLocal for LOCAL provider");
        }
        return UserAccountData.builder()
            .userId(userId)
            .email(email)
            .providerType(providerType)
            .build();
    }

    public static UserAccountData createForLocal(UserId userId, String email, String passwordHash) {
        return UserAccountData.builder()
            .userId(userId)
            .email(email)
            .providerType(ProviderType.LOCAL)
            .passwordHash(passwordHash)
            .build();
    }

    public static UserAccountData createForLocalWithMandatoryChange(
            UserId userId, String email, String passwordHash) {
        return UserAccountData.builder()
            .userId(userId)
            .email(email)
            .providerType(ProviderType.LOCAL)
            .passwordHash(passwordHash)
            .mustChangePassword(true)
            .build();
    }

    public void changePasswordHash(String newHash) {
        Objects.requireNonNull(newHash, "newHash must not be null");
        this.passwordHash = newHash;
        this.mustChangePassword = false;
    }

    public void requirePasswordChange(String newHash) {
        Objects.requireNonNull(newHash, "newHash must not be null");
        this.passwordHash = newHash;
        this.mustChangePassword = true;
    }

    public void recordLogin() {
        this.lastLoginAt = LocalDateTime.now();
    }

    public void withdraw() {
        if (isWithdrawn()) {
            return; // idempotent
        }
        this.withdrawnAt = LocalDateTime.now();
        this.email = "withdrawn-" + this.userId.getUid() + "@withdrawn.local";
        registerEvent(new UserAccountWithdrawnEvent(this.userId.getUid(), this.email));
    }

    public boolean isWithdrawn() {
        return withdrawnAt != null;
    }

    /**
     * Seed-only API: replaces the V5-seeded placeholder email and password hash
     * with operator-supplied values. Called exactly once per environment by
     * SuperAdminSeedService at ApplicationReadyEvent. Do NOT call from normal
     * application flow — there is no IAM lifecycle event for this.
     */
    public void replacePlaceholderCredentials(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }
}
