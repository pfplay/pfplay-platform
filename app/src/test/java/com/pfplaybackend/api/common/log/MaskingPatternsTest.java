package com.pfplaybackend.api.common.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingPatternsTest {

    // --- JWT ---
    @Test
    @DisplayName("JWT — 표준 3-segment 토큰 매치")
    void jwt_matches_standard() {
        String input = "Authorization=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.xyz_signature";
        String out = MaskingPatterns.JWT.matcher(input).replaceAll("<jwt-redacted>");
        assertThat(out).isEqualTo("Authorization=<jwt-redacted>");
    }

    // --- PASSWORD_KV ---
    @Test
    @DisplayName("PASSWORD_KV — password=, password_hash:, pwd= 모두 매치")
    void password_kv_matches_variants() {
        assertThat(MaskingPatterns.PASSWORD_KV.matcher("user=alice, password=secret123, role=admin")
                .replaceAll("$1=<redacted>"))
                .isEqualTo("user=alice, password=<redacted>, role=admin");

        assertThat(MaskingPatterns.PASSWORD_KV.matcher("password_hash: $argon2id$xyz")
                .replaceAll("$1=<redacted>"))
                .contains("password_hash=<redacted>");
    }

    // --- BEARER_TOKEN ---
    @Test
    @DisplayName("BEARER_TOKEN — Authorization 헤더 형식")
    void bearer_token_matches() {
        String out = MaskingPatterns.BEARER_TOKEN.matcher("Authorization: Bearer abc.def-XYZ_123")
                .replaceAll("Bearer <redacted>");
        assertThat(out).isEqualTo("Authorization: Bearer <redacted>");
    }

    // --- Cookie variants ---
    @Test
    @DisplayName("AdminAccessToken / SharedSessionToken 쿠키 값 매치")
    void cookie_tokens_match() {
        String input = "Cookie: AdminAccessToken=abc.def.xyz; SharedSessionToken=alpha.beta";
        String afterAdmin = MaskingPatterns.ADMIN_ACCESS_TOKEN_COOKIE.matcher(input)
                .replaceAll("AdminAccessToken=<redacted>");
        String afterShared = MaskingPatterns.SHARED_SESSION_TOKEN_COOKIE.matcher(afterAdmin)
                .replaceAll("SharedSessionToken=<redacted>");
        assertThat(afterShared).doesNotContain("abc.def.xyz")
                                .doesNotContain("alpha.beta")
                                .contains("AdminAccessToken=<redacted>")
                                .contains("SharedSessionToken=<redacted>");
    }

    // --- XSRF_TOKEN ---
    @Test
    @DisplayName("X-XSRF-TOKEN 헤더 값 매치")
    void xsrf_token_matches() {
        String out = MaskingPatterns.XSRF_TOKEN.matcher("X-XSRF-TOKEN: csrf-abc-123")
                .replaceAll("$1=<redacted>");
        assertThat(out).contains("X-XSRF-TOKEN=<redacted>");
    }

    // --- API_KEY_KV ---
    @Test
    @DisplayName("API_KEY_KV — api-key/apikey/secret-key/client-secret 모두 매치")
    void api_key_variants_match() {
        assertThat(MaskingPatterns.API_KEY_KV.matcher("api_key=KEY_123-abc").replaceAll("$1=<redacted>"))
                .isEqualTo("api_key=<redacted>");
        assertThat(MaskingPatterns.API_KEY_KV.matcher("client_secret: my-secret-value").replaceAll("$1=<redacted>"))
                .contains("client_secret=<redacted>");
    }

    // --- EMAIL ---
    @Test
    @DisplayName("EMAIL — 표준 길이 local-part 첫 1자만 노출")
    void email_masks_standard() {
        String out = MaskingPatterns.EMAIL.matcher("contact john.doe@example.com please")
                .replaceAll("$1***@$2");
        assertThat(out).isEqualTo("contact j***@example.com please");
    }

    @Test
    @DisplayName("EMAIL — 1-char local-part 도 leak 없이 마스킹")
    void email_1char_local_no_leak() {
        String out = MaskingPatterns.EMAIL.matcher("a@example.com")
                .replaceAll("$1***@$2");
        assertThat(out).isEqualTo("a***@example.com");
    }

    // --- IP_V4 ---
    @Test
    @DisplayName("IP_V4 — 표준 dotted-quad 마지막 옥텟 마스킹")
    void ip_v4_masks_standard() {
        String out = MaskingPatterns.IP_V4.matcher("client 192.168.1.42 connected")
                .replaceAll("$1.xxx");
        assertThat(out).isEqualTo("client 192.168.1.xxx connected");
    }

    @Test
    @DisplayName("IP_V4 — 5+ octet decimal sequence 의 inner overlap 미매칭")
    void ip_v4_inner_overlap_rejected() {
        // `cluster 192.168.1.1.2.3` 같은 6-decimal sequence:
        // lookbehind/lookahead 가 `192.168.1.1` 도 `168.1.1.2` 도 막아야 함
        String out = MaskingPatterns.IP_V4.matcher("cluster 192.168.1.1.2.3 build")
                .replaceAll("$1.xxx");
        // 6-decimal sequence 어느 4-window 도 매치 안 됨 → 무변형
        assertThat(out).isEqualTo("cluster 192.168.1.1.2.3 build");
    }

    @Test
    @DisplayName("IP_V4 limitation — semver-like 4-decimal 단일 시퀀스는 의도적 redact")
    void ip_v4_semver_redacted_intentionally() {
        // spec §6.3 accepted limitation 검증 — 정규식이 IP 와 semver 구분 불가
        String out = MaskingPatterns.IP_V4.matcher("version 1.2.3.4 build")
                .replaceAll("$1.xxx");
        assertThat(out).isEqualTo("version 1.2.3.xxx build");
    }

    // --- empty / multi-line ---
    @Test
    @DisplayName("empty string — 어느 패턴도 NPE 없이 통과")
    void empty_string_safe() {
        assertThat(MaskingPatterns.EMAIL.matcher("").replaceAll("$1***@$2")).isEmpty();
        assertThat(MaskingPatterns.IP_V4.matcher("").replaceAll("$1.xxx")).isEmpty();
        assertThat(MaskingPatterns.JWT.matcher("").replaceAll("<jwt-redacted>")).isEmpty();
    }

    @Test
    @DisplayName("multi-line stack trace — 여러 PII 인스턴스 모두 매치")
    void multi_line_multiple_matches() {
        String input = "at Service.connect(10.0.0.5:443)\n" +
                       "Caused by: timeout for alice@corp.io\n" +
                       "  at retry(10.0.0.6:443) for bob@corp.io";
        String afterEmail = MaskingPatterns.EMAIL.matcher(input).replaceAll("$1***@$2");
        String afterIp = MaskingPatterns.IP_V4.matcher(afterEmail).replaceAll("$1.xxx");
        assertThat(afterIp).contains("10.0.0.xxx:443").contains("a***@corp.io").contains("b***@corp.io")
                           .doesNotContain("alice@corp.io").doesNotContain("bob@corp.io")
                           .doesNotContain("10.0.0.5").doesNotContain("10.0.0.6");
    }
}
