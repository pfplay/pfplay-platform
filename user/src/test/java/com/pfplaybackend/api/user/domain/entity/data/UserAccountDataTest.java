package com.pfplaybackend.api.user.domain.entity.data;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.domain.event.UserAccountWithdrawnEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAccountDataTest {

    @Test
    void create_setsRequiredFields() {
        var account = UserAccountData.createForSocial(
            new UserId(123L), "alice@gmail.com", ProviderType.GOOGLE);

        assertThat(account.getUserId().getUid()).isEqualTo(123L);
        assertThat(account.getEmail()).isEqualTo("alice@gmail.com");
        assertThat(account.getProviderType()).isEqualTo(ProviderType.GOOGLE);
        assertThat(account.getPasswordHash()).isNull();
        assertThat(account.getLastLoginAt()).isNull();
        assertThat(account.getWithdrawnAt()).isNull();
    }

    @Test
    void createForLocal_setsPasswordHash() {
        var account = UserAccountData.createForLocal(
            new UserId(1L), "admin@pfplay.local", "$2a$12$bcrypted...");

        assertThat(account.getProviderType()).isEqualTo(ProviderType.LOCAL);
        assertThat(account.getPasswordHash()).isEqualTo("$2a$12$bcrypted...");
    }

    @Test
    void withdraw_setsWithdrawnAtAndAnonymizesEmail() {
        var account = UserAccountData.createForSocial(
            new UserId(1L), "alice@gmail.com", ProviderType.GOOGLE);

        account.withdraw();

        assertThat(account.getWithdrawnAt()).isNotNull();
        assertThat(account.getEmail()).startsWith("withdrawn-").endsWith("@withdrawn.local");
    }

    @Test
    void withdraw_registersUserAccountWithdrawnEvent() {
        var account = UserAccountData.createForSocial(
            new UserId(7L), "alice@gmail.com", ProviderType.GOOGLE);

        account.withdraw();

        var events = account.pollDomainEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(UserAccountWithdrawnEvent.class);
        var withdrawn = (UserAccountWithdrawnEvent) events.get(0);
        assertThat(withdrawn.getUserAccountId()).isEqualTo(7L);
        assertThat(withdrawn.getAnonymizedEmail()).startsWith("withdrawn-7@");
    }

    @Test
    void recordLogin_updatesLastLoginAt() {
        var account = UserAccountData.createForSocial(
            new UserId(1L), "alice@gmail.com", ProviderType.GOOGLE);
        assertThat(account.getLastLoginAt()).isNull();

        account.recordLogin();

        assertThat(account.getLastLoginAt()).isNotNull();
    }

    @Test
    void withdraw_isIdempotent() {
        var account = UserAccountData.createForSocial(
            new UserId(1L), "alice@gmail.com", ProviderType.GOOGLE);
        account.withdraw();
        var firstWithdrawnAt = account.getWithdrawnAt();
        var firstEmail = account.getEmail();
        account.pollDomainEvents(); // drain first event

        account.withdraw(); // second call should be no-op

        assertThat(account.getWithdrawnAt()).isEqualTo(firstWithdrawnAt);
        assertThat(account.getEmail()).isEqualTo(firstEmail);
        assertThat(account.pollDomainEvents()).isEmpty();
    }

    @Test
    void createForSocial_rejectsLocalProvider() {
        assertThatThrownBy(() -> UserAccountData.createForSocial(
            new UserId(1L), "x@y.com", ProviderType.LOCAL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LOCAL");
    }

    @Test
    void createForSocial_rejectsNullProviderType() {
        assertThatThrownBy(() -> UserAccountData.createForSocial(
            new UserId(1L), "x@y.com", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("providerType");
    }

    @Test
    void replacePlaceholderCredentials_mutatesEmailAndHash() {
        var account = UserAccountData.createForLocal(
            new UserId(1L), "__placeholder__", "__placeholder_hash__");

        account.replacePlaceholderCredentials("real@admin.com", "$2a$12$encoded");

        assertThat(account.getEmail()).isEqualTo("real@admin.com");
        assertThat(account.getPasswordHash()).isEqualTo("$2a$12$encoded");
    }
}
