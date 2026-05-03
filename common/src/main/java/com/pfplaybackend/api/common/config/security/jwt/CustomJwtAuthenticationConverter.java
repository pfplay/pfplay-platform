package com.pfplaybackend.api.common.config.security.jwt;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("JWT missing required 'sub' claim");
        }
        UserId userId = UserId.fromString(subject);
        String email = jwt.getClaim("email");

        List<String> levels = jwt.getClaim("access_level");
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException("JWT missing required 'access_level' claim");
        }
        List<GrantedAuthority> authorities = levels.stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();

        String tier = jwt.getClaim("authority_tier");
        AuthorityTier authorityTier = StringUtils.hasText(tier) ? AuthorityTier.valueOf(tier) : null;

        return new CustomJwtAuthenticationToken(jwt, authorities, userId, email, authorityTier);
    }
}
