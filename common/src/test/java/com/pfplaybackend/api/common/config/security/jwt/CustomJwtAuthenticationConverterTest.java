package com.pfplaybackend.api.common.config.security.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomJwtAuthenticationConverterTest {

    private final CustomJwtAuthenticationConverter sut = new CustomJwtAuthenticationConverter();

    @Test
    void array_access_level_yields_one_authority_per_entry() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject("1000000000000001")
                .claim("email", "super@pfplay.xyz")
                .claim("access_level", List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"))
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(900))
                .build();

        AbstractAuthenticationToken auth = sut.convert(jwt);

        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        assertThat(((CustomJwtAuthenticationToken) auth).getAuthorityTier()).isNull();
    }

    @Test
    void member_token_with_authority_tier_is_parsed() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject("1000000000000042")
                .claim("email", "user@pfplay.xyz")
                .claim("access_level", List.of("ROLE_MEMBER"))
                .claim("authority_tier", "FM")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(86400))
                .build();

        AbstractAuthenticationToken auth = sut.convert(jwt);

        assertThat(((CustomJwtAuthenticationToken) auth).getAuthorityTier().name()).isEqualTo("FM");
    }

    @Test
    void missing_subject_throws() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .claim("email", "x@x.com")
                .claim("access_level", List.of("ROLE_MEMBER"))
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60))
                .build();

        assertThatThrownBy(() -> sut.convert(jwt))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
