package com.pfplaybackend.api.common.config.security.authorization;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Centralized authorization SpEL bean — exposed under the bean name {@code adminAuth}
 * so controllers can declare {@code @PreAuthorize("@adminAuth.canSuspendPartyroom()")}.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-04-19-admin-platform-security.md} §5.2.4.
 *
 * <p>Each method is intent-named (vs. role-named) so a future RBAC migration to a
 * permission table can change the bodies without touching controllers. Today, each
 * method is a thin authority lookup against the {@link SecurityContextHolder}.
 */
@Component("adminAuth")
public class AdminAuthorizationSpEL {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    public boolean isAdmin() {
        return hasAuthority(ROLE_ADMIN);
    }

    public boolean isSuperAdmin() {
        return hasAuthority(ROLE_SUPER_ADMIN);
    }

    public boolean canManageAdmins() {
        return isSuperAdmin();
    }

    public boolean canSuspendPartyroom() {
        return isAdmin();
    }

    public boolean canChangeMemberTier() {
        return isAdmin();
    }

    public boolean canManageMembers() {
        return isAdmin();
    }

    public boolean canHandleReports() {
        return isAdmin();
    }

    public boolean canManageAvatarResources() {
        return isSuperAdmin();
    }

    public boolean canManageVirtualDj() {
        return isAdmin();
    }

    private boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (authority.equals(ga.getAuthority())) return true;
        }
        return false;
    }
}
