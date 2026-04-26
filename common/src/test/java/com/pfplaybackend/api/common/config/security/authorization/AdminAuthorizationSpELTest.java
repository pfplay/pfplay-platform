package com.pfplaybackend.api.common.config.security.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminAuthorizationSpEL}, the @adminAuth SpEL bean.
 *
 * <p>Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.2.4
 *
 * <p>The bean is intent-named (canManageAdmins, canSuspendPartyroom, ...). Today
 * each method is a thin role check; future RBAC re-evaluation will swap to
 * permission-table lookups without changing controllers.
 */
class AdminAuthorizationSpELTest {

    private final AdminAuthorizationSpEL adminAuth = new AdminAuthorizationSpEL();

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(String... authorities) {
        var auths = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        var token = new UsernamePasswordAuthenticationToken("user", "n/a", auths);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(token);
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    void isSuperAdmin_returnsTrue_whenPrincipalHasRoleSuperAdmin() {
        authenticateWith("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(adminAuth.isSuperAdmin()).isTrue();
    }

    @Test
    void isSuperAdmin_returnsFalse_forPlainAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.isSuperAdmin()).isFalse();
    }

    @Test
    void isAdmin_returnsTrue_forAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_returnsTrue_forSuperAdmin() {
        // Super admin carries both ROLE_ADMIN and ROLE_SUPER_ADMIN authorities (post-PR 4 issuance).
        authenticateWith("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(adminAuth.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_returnsFalse_forMember() {
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.isAdmin()).isFalse();
    }

    @Test
    void canManageAdmins_requiresSuperAdmin() {
        authenticateWith("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(adminAuth.canManageAdmins()).isTrue();
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canManageAdmins()).isFalse();
    }

    @Test
    void canSuspendPartyroom_requiresAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canSuspendPartyroom()).isTrue();
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.canSuspendPartyroom()).isFalse();
    }

    @Test
    void canChangeMemberTier_requiresAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canChangeMemberTier()).isTrue();
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.canChangeMemberTier()).isFalse();
    }

    @Test
    void canManageMembers_requiresAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canManageMembers()).isTrue();
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.canManageMembers()).isFalse();
    }

    @Test
    void canHandleReports_requiresAdmin() {
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canHandleReports()).isTrue();
        authenticateWith("ROLE_MEMBER");
        assertThat(adminAuth.canHandleReports()).isFalse();
    }

    @Test
    void canManageAvatarResources_requiresSuperAdmin() {
        authenticateWith("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(adminAuth.canManageAvatarResources()).isTrue();
        authenticateWith("ROLE_ADMIN");
        assertThat(adminAuth.canManageAvatarResources()).isFalse();
    }

    @Test
    void allMethods_returnFalse_whenNoAuthentication() {
        SecurityContextHolder.clearContext();
        assertThat(adminAuth.isAdmin()).isFalse();
        assertThat(adminAuth.isSuperAdmin()).isFalse();
        assertThat(adminAuth.canManageAdmins()).isFalse();
        assertThat(adminAuth.canSuspendPartyroom()).isFalse();
        assertThat(adminAuth.canChangeMemberTier()).isFalse();
        assertThat(adminAuth.canManageMembers()).isFalse();
        assertThat(adminAuth.canHandleReports()).isFalse();
        assertThat(adminAuth.canManageAvatarResources()).isFalse();
    }
}
