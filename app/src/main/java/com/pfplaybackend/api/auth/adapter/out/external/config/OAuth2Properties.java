package com.pfplaybackend.api.auth.adapter.out.external.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.oauth2")
public class OAuth2Properties {

    /**
     * OAuth 제공자별 설정 맵
     * Key: 제공자 이름 (google, twitter, github 등)
     * Value: Provider 설정 객체
     */
    private Map<String, Provider> providers;

    /**
     * 개별 OAuth 제공자 설정
     */
    @Data
    public static class Provider {

        // ===== 필수 OAuth 설정 =====

        /**
         * OAuth 클라이언트 ID
         */
        private String clientId;

        /**
         * OAuth 클라이언트 시크릿
         */
        private String clientSecret;

        /**
         * 인증 완료 후 리다이렉트될 URI
         */
        private String redirectUri;

        /**
         * 사용자 인증을 위한 Authorization Server URL
         * 예: https://accounts.google.com/oauth/authorize
         */
        private String authorizationUri;

        /**
         * Authorization Code를 Access Token으로 교환하는 엔드포인트
         * 예: https://oauth2.googleapis.com/token
         */
        private String tokenUri;

        /**
         * Access Token으로 사용자 정보를 조회하는 엔드포인트
         * 예: https://www.googleapis.com/oauth2/v2/userinfo
         */
        private String userInfoUri;

        /**
         * 요청할 OAuth 스코프 목록
         * 예: [openid, email, profile]
         */
        private List<String> scopes;

        // ===== 제공자별 선택적 설정 =====

        /**
         * Google 전용: access_type 파라미터
         * - "offline": Refresh Token 발급 (권장)
         * - "online": Refresh Token 발급 안함
         */
        private String accessType;

        /**
         * Google 전용: prompt 파라미터
         * - "consent": 항상 동의 화면 표시
         * - "select_account": 계정 선택 화면 표시
         * - "none": 인터랙션 없이 자동 로그인 시도
         */
        private String prompt;

        // ===== 헬퍼 메서드 =====

        /**
         * 스코프를 공백(%20) 으로 구분된 URL 인코딩 문자열로 반환 (authorize URL scope 파라미터용).
         *
         * 주의: 리터럴 '+' 로 join 하면 안 된다 — X(Twitter) OAuth2 authorize 는 query 의
         * '+' 를 공백으로 디코딩하지 않고 리터럴 처리하므로 "a+b" 가 단일 무효 스코프가 되어
         * 토큰 스코프 실효 0 → /2/users/me 403. 공백은 '%20' 으로 인코딩한다.
         * (bugs/2026-05-17-twitter-oauth-login-401.md, pfplay-platform#214)
         *
         * @return 예: "users.read%20tweet.read"
         */
        public String getScopesAsUrlEncoded() {
            return scopes != null ? String.join("%20", scopes) : "";
        }

        /**
         * 스코프를 공백으로 구분된 문자열로 반환 (OAuth 표준 형식)
         *
         * @return 예: "openid email profile"
         */
        public String getScopesAsString() {
            return scopes != null ? String.join(" ", scopes) : "";
        }

        /**
         * 필수 설정이 모두 있는지 검증
         *
         * @return 유효하면 true
         */
        public boolean isValid() {
            return clientId != null && !clientId.isBlank() &&
                    clientSecret != null && !clientSecret.isBlank() &&
                    redirectUri != null && !redirectUri.isBlank() &&
                    authorizationUri != null && !authorizationUri.isBlank() &&
                    tokenUri != null && !tokenUri.isBlank() &&
                    userInfoUri != null && !userInfoUri.isBlank() &&
                    scopes != null && !scopes.isEmpty();
        }

        /**
         * Google 제공자인지 확인
         */
        public boolean isGoogle() {
            return authorizationUri != null &&
                    authorizationUri.contains("accounts.google.com");
        }

        /**
         * Twitter 제공자인지 확인
         */
        public boolean isTwitter() {
            return authorizationUri != null &&
                    authorizationUri.contains("twitter.com");
        }

        /**
         * GitHub 제공자인지 확인
         */
        public boolean isGitHub() {
            return authorizationUri != null &&
                    authorizationUri.contains("github.com");
        }
    }

    /**
     * 특정 제공자 설정 조회
     *
     * @param providerName 제공자 이름 (google, twitter, github)
     * @return Provider 설정 객체
     * @throws IllegalArgumentException 설정이 없거나 유효하지 않은 경우
     */
    public Provider getProvider(String providerName) {
        if (providers == null || !providers.containsKey(providerName)) {
            throw new IllegalArgumentException("OAuth provider not configured: " + providerName);
        }

        Provider provider = providers.get(providerName);
        if (!provider.isValid()) {
            throw new IllegalArgumentException("Invalid OAuth provider configuration: " + providerName);
        }

        return provider;
    }

    /**
     * 설정된 모든 제공자 이름 목록 반환
     */
    public java.util.Set<String> getProviderNames() {
        return providers != null ? providers.keySet() : java.util.Set.of();
    }

    /**
     * 유효한 제공자 설정만 필터링하여 반환
     */
    public Map<String, Provider> getValidProviders() {
        if (providers == null) {
            return Map.of();
        }

        return providers.entrySet().stream()
                .filter(entry -> entry.getValue().isValid())
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }
}
