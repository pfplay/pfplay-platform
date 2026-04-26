package com.pfplaybackend.api.administration.application;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationToken;
import com.pfplaybackend.api.common.domain.value.UserId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class AdminContextTest {

    private final AdministratorRepository administratorRepository = mock(AdministratorRepository.class);
    private final AdminContext adminContext = new AdminContext(administratorRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentUserId_authenticated_returnsUserId() {
        UserId expected = new UserId(42L);
        SecurityContextHolder.getContext().setAuthentication(stubToken(expected));
        assertThat(adminContext.currentUserId()).isEqualTo(expected);
    }

    @Test
    void currentUserId_anonymous_throwsIllegalState() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken(
                        "k", "anon", List.of(new SimpleGrantedAuthority("ROLE_ANON"))));
        assertThatThrownBy(adminContext::currentUserId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentUserId_noAuthentication_throwsIllegalState() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(adminContext::currentUserId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentAdministratorId_resolvesViaRepository() {
        UserId userId = new UserId(42L);
        AdministratorData admin = mock(AdministratorData.class);
        given(admin.getAdministratorId()).willReturn(7L);
        given(administratorRepository.findByUserAccountId(42L)).willReturn(Optional.of(admin));
        SecurityContextHolder.getContext().setAuthentication(stubToken(userId));

        assertThat(adminContext.currentAdministratorId()).isEqualTo(7L);
    }

    @Test
    void currentAdministratorId_noAdministratorRow_throwsIllegalState() {
        SecurityContextHolder.getContext().setAuthentication(stubToken(new UserId(42L)));
        given(administratorRepository.findByUserAccountId(42L)).willReturn(Optional.empty());
        assertThatThrownBy(adminContext::currentAdministratorId)
                .isInstanceOf(IllegalStateException.class);
    }

    private static CustomJwtAuthenticationToken stubToken(UserId userId) {
        Jwt jwt = Jwt.withTokenValue("x")
                .header("alg", "none")
                .subject(String.valueOf(userId.getUid()))
                .build();
        return new CustomJwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                userId,
                "x@y.z",
                null);
    }
}
