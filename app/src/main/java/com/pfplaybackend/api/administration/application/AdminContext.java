package com.pfplaybackend.api.administration.application;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationToken;
import com.pfplaybackend.api.common.domain.value.UserId;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Codifies the existing pattern (LogoutService.java:20-21, PartyContextAspect.java:25-26)
 * for resolving the current authenticated admin's identity from SecurityContext.
 *
 * <p>{@link #currentUserId()} reads the JWT-bound {@link UserId} (i.e. user_account_id).
 * {@link #currentAdministratorId()} additionally translates that to the matching
 * {@code administrator_id} via {@link AdministratorRepository}. Throws
 * {@link IllegalStateException} for unauthenticated callers — paths that reach this
 * helper are gated by {@code @PreAuthorize("@adminAuth.isAdmin()")} or stricter, so
 * a missing principal indicates a wiring bug rather than a user error.
 */
@Component
@RequiredArgsConstructor
public class AdminContext {

    private final AdministratorRepository administratorRepository;

    public UserId currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof CustomJwtAuthenticationToken token) {
            return token.getUserId();
        }
        throw new IllegalStateException("admin context: no authenticated principal");
    }

    public Long currentAdministratorId() {
        UserId userId = currentUserId();
        AdministratorData admin = administratorRepository
                .findByUserAccountId(userId.getUid())
                .orElseThrow(() -> new IllegalStateException(
                        "admin context: no administrator row for user_id=" + userId.getUid()));
        return admin.getAdministratorId();
    }
}
