package com.pfplaybackend.api.common.log;

import java.util.regex.Pattern;

/**
 * Log 출력 시 마스킹 대상 정규식 카탈로그.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-observability-b1-b2-design.md §6.1.
 *
 * <p>두 부류:
 * <ul>
 *   <li>secret — 완전 마스킹 (`<redacted>`)</li>
 *   <li>PII — 식별 가능성 차단하되 디버깅 단서 (앞글자 / 마지막 옥텟 제외) 유지</li>
 * </ul>
 *
 * <p>새 secret 패턴 발견 시 본 카탈로그에 추가 + {@link MaskingPatternsTest} 에 케이스 추가.
 */
public final class MaskingPatterns {

    private MaskingPatterns() {}

    // --- Secret — 완전 마스킹 ---
    public static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    public static final Pattern PASSWORD_KV = Pattern.compile(
            "(?i)(password|password_hash|passwordhash|pwd)[\"']?\\s*[:=]\\s*[\"']?[^\\s,\"'}]+");
    public static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)Bearer\\s+[A-Za-z0-9._-]+");
    public static final Pattern XSRF_TOKEN = Pattern.compile(
            "(?i)(X-XSRF-TOKEN|XSRF-TOKEN)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9-]+");
    public static final Pattern ADMIN_ACCESS_TOKEN_COOKIE = Pattern.compile(
            "(?i)AdminAccessToken[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]+");
    public static final Pattern SHARED_SESSION_TOKEN_COOKIE = Pattern.compile(
            "(?i)SharedSessionToken[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]+");
    public static final Pattern API_KEY_KV = Pattern.compile(
            "(?i)(api[_-]?key|apikey|secret[_-]?key|client[_-]?secret)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._-]+");

    // --- PII — 일부 마스킹 ---
    /** 첫 1자만 노출 — {@code {1}} 이라 1-char local-part 도 매치 (leak 방지). */
    public static final Pattern EMAIL = Pattern.compile(
            "([\\w.+-])[\\w.+-]*@([\\w-]+(?:\\.[\\w-]+)+)");
    /** lookbehind/lookahead 로 5+ octet decimal sequence 부분 매치 방지.
     *  semver-like 단일 시퀀스는 의도적으로 redact (spec §6.3 limitation). */
    public static final Pattern IP_V4 = Pattern.compile(
            "(?<![\\d.])(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}(?!\\d)");
}
