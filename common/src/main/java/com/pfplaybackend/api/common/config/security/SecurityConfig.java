package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.config.security.cors.properties.CorsProperties;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.AdminTokenRenewalFilter;
import com.pfplaybackend.api.common.config.security.jwt.CookieBearerTokenResolver;
import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationConverter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.config.security.web.AdminOriginGuardFilter;
import com.pfplaybackend.api.common.config.security.web.properties.AdminOriginProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    private final CookieBearerTokenResolver customBearerTokenResolver;
    private final CustomJwtAuthenticationConverter jwtAuthenticationConverter;
    private final CorsProperties corsProperties;
    private final JwtProperties jwtProperties;
    private final JwtService jwtService;
    private final AdminCookieWriter adminCookieWriter;
    private final AdminOriginProperties adminOriginProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessionManagement ->
                        sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .headers(headerConfig -> headerConfig
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(request -> request
                        .requestMatchers("/api/v1/auth/oauth/callback", "/api/v1/auth/oauth/url", "/api/v1/auth/logout",
                                "/api/v1/auth/admin/login",
                                "/api/v1/users/members/sign/**", "/api/v1/users/guests/sign/**", "/api/v1/partyrooms/link/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/spec/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Spec §5.2.3 — order matters: most specific first.
                        .requestMatchers("/api/v1/admin/system/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/avatar/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/auth/admin/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(customBearerTokenResolver)
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                )
                .addFilterBefore(adminOriginGuardFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(adminTokenRenewalFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public AdminTokenRenewalFilter adminTokenRenewalFilter() {
        return new AdminTokenRenewalFilter(jwtProperties, jwtService, adminCookieWriter);
    }

    @Bean
    public AdminOriginGuardFilter adminOriginGuardFilter() {
        return new AdminOriginGuardFilter(adminOriginProperties);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(corsProperties.getAllowedMethods());
        configuration.setAllowedHeaders(corsProperties.getAllowedHeaders());
        configuration.setAllowCredentials(corsProperties.isAllowCredentials());
        configuration.setMaxAge(corsProperties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * BCrypt password encoder with cost 12 for admin local login.
     * Spec: docs/superpowers/specs/2026-04-19-admin-platform-security.md §5.5.1
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
