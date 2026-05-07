package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.config.security.enums.AccessLevel;
import com.pfplaybackend.api.common.config.security.jwt.dto.TokenClaimsRequest;
import com.pfplaybackend.api.common.config.security.jwt.enums.TokenClaim;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.exception.AuthenticationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;
    private final Clock clock;

    public String mintAdminAccessToken(TokenClaimsRequest claims) {
        return mint(claims, jwtProperties.getAdminAccessTokenExpirationMs());
    }

    public String mintSharedSessionToken(TokenClaimsRequest claims) {
        return mint(claims, jwtProperties.getSharedSessionTokenExpirationMs());
    }

    private String mint(TokenClaimsRequest req, long ttlMs) {
        Date now = Date.from(clock.instant());
        Date exp = new Date(now.getTime() + ttlMs);

        Map<String, Object> custom = new HashMap<>();
        custom.put(TokenClaim.EMAIL.getValue(), req.email());
        custom.put(TokenClaim.ACCESS_LEVEL.getValue(),
                req.accessLevels().stream().map(AccessLevel::name).toList());
        if (req.authorityTier() != null) {
            custom.put(TokenClaim.AUTHORITY_TIER.getValue(), req.authorityTier().name());
        }

        return Jwts.builder()
                .claims(custom)
                .subject(req.subject())
                .issuedAt(now)
                .expiration(exp)
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    public boolean validate(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (Exception e) {
            log.debug("Invalid token: {}", e.getMessage());
            return false;
        }
    }

    public Claims extractClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new AuthenticationException("Token has expired");
        } catch (MalformedJwtException e) {
            throw new AuthenticationException("Invalid token format");
        } catch (Exception e) {
            throw new AuthenticationException("Token validation failed");
        }
    }

    public long timeUntilExpiryMs(String token) {
        Date exp = extractClaims(token).getExpiration();
        return exp.getTime() - clock.millis();
    }

    public List<String> getAccessLevels(String token) {
        Object raw = extractClaims(token).get(TokenClaim.ACCESS_LEVEL.getValue());
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    public String getSubject(String token) {
        return extractClaims(token).getSubject();
    }

    public String getEmail(String token) {
        return extractClaims(token).get(TokenClaim.EMAIL.getValue(), String.class);
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
