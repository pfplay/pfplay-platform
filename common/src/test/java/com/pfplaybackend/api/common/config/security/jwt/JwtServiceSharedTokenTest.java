package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceSharedTokenTest {

    private JwtService jwtService;
    private JwtProperties props;

    @BeforeEach
    void setup() {
        props = new JwtProperties();
        props.setSecret("test-secret-must-be-at-least-32-bytes-long-for-hs256");
        props.setAdminAccessTokenExpirationMs(900_000L);
        props.setSharedSessionTokenExpirationMs(86_400_000L);
        Clock fixed = Clock.fixed(Instant.parse("2026-04-26T00:00:00Z"), ZoneOffset.UTC);
        jwtService = new JwtService(props, fixed);
    }

    @Test
    void shared_token_carries_authority_tier_when_present() {
        var req = new TokenClaimsRequest(
                "1000000000000042",
                "user@pfplay.xyz",
                List.of(AccessLevel.ROLE_MEMBER),
                AuthorityTier.FM
        );

        String token = jwtService.mintSharedSessionToken(req);
        Claims c = parse(token);

        assertThat(c.getSubject()).isEqualTo("1000000000000042");
        assertThat(c.get("authority_tier", String.class)).isEqualTo("FM");
        assertThat(c.getExpiration().toInstant())
                .isEqualTo(Instant.parse("2026-04-27T00:00:00Z"));
    }

    @Test
    void shared_token_authority_tier_omitted_when_null() {
        var req = new TokenClaimsRequest(
                "1000000000000099",
                "tierless@pfplay.xyz",
                List.of(AccessLevel.ROLE_MEMBER),
                null
        );

        String token = jwtService.mintSharedSessionToken(req);
        Claims c = parse(token);

        assertThat(c.get("authority_tier")).isNull();
    }

    private Claims parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .clockSkewSeconds(Long.MAX_VALUE / 2_000)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
