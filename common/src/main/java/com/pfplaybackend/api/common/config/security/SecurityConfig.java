package com.pfplaybackend.api.common.config.security;

import com.pfplaybackend.api.common.config.security.cors.properties.CorsProperties;
import com.pfplaybackend.api.common.config.security.csrf.AdminCsrfRequestMatcher;
import com.pfplaybackend.api.common.config.security.csrf.AdminCsrfTokenRepositoryFactory;
import com.pfplaybackend.api.common.config.security.csrf.EagerCsrfTokenRequestAttributeHandler;
import com.pfplaybackend.api.common.config.security.csrf.properties.AdminCsrfProperties;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.AdminTokenRenewalFilter;
import com.pfplaybackend.api.common.config.security.jwt.CookieBearerTokenResolver;
import com.pfplaybackend.api.common.config.security.jwt.CustomJwtAuthenticationConverter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import com.pfplaybackend.api.common.config.security.web.AdminOriginGuardFilter;
import com.pfplaybackend.api.common.config.security.web.properties.AdminOriginProperties;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.ObjectPostProcessor;
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
    private final AdminCsrfTokenRepositoryFactory adminCsrfTokenRepositoryFactory;
    private final AdminCsrfProperties adminCsrfProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> {
                    if (!adminCsrfProperties.isEnabled()) {
                        csrf.disable();
                        return;
                    }
                    CookieCsrfTokenRepository repo = adminCsrfTokenRepositoryFactory.build();
                    // Eager handler resolves the deferred token at handle() time so the
                    // post-rotation Set-Cookie is emitted while the response is still
                    // uncommitted. Without this, mutations after login hit 403 because
                    // the browser sees only the deletion cookie. Spec: admin-backend-asks.md A6.
                    CsrfTokenRequestAttributeHandler handler = new EagerCsrfTokenRequestAttributeHandler();
                    AdminCsrfRequestMatcher adminMatcher = new AdminCsrfRequestMatcher();
                    csrf
                            .csrfTokenRepository(repo)
                            .csrfTokenRequestHandler(handler)
                            .requireCsrfProtectionMatcher(adminMatcher)
                            .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                                @Override
                                public <O extends CsrfFilter> O postProcess(O filter) {
                                    // OAuth2ResourceServerConfigurer.init()이 csrf.ignoringRequestMatchers(BearerTokenRequestMatcher)를
                                    // 강제 호출하여 CsrfConfigurer.getRequireCsrfProtectionMatcher()가 우리 matcher를
                                    // And(ours, Not(Or(BearerTokenRequestMatcher))) 로 wrapping한다. 우리는 cookie-based
                                    // bearer auth라 모든 인증된 admin 요청이 BearerTokenRequestMatcher에 매칭되어 CSRF
                                    // 검증이 통째로 skip되는 보안 결함이 됨. post-processor에서 wrapping을 풀고 우리
                                    // matcher를 직접 박는다 (admin-backend-asks.md A6 후속 fix).
                                    filter.setRequireCsrfProtectionMatcher(adminMatcher);
                                    filter.setAccessDeniedHandler((req, res, ex) -> {
                                        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                        res.getWriter().write("{\"status\":403,\"error\":\"CSRF token validation failed\"}");
                                    });
                                    return filter;
                                }
                            });
                })
                .sessionManagement(sessionManagement ->
                        sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .headers(headerConfig -> headerConfig
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(request -> request
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/auth/oauth/callback", "/api/v1/auth/oauth/url", "/api/v1/auth/logout",
                                "/api/v1/auth/admin/login",
                                "/api/v1/users/members/sign/**", "/api/v1/users/guests/sign/**", "/api/v1/partyrooms/link/**").permitAll()
                        // V14 시스템 공지 §4.4 — anonymous public status endpoint (10초 cache).
                        .requestMatchers("/api/v1/system/status").permitAll()
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
