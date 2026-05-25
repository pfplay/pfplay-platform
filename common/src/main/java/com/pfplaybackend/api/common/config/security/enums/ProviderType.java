package com.pfplaybackend.api.common.config.security.enums;

public enum ProviderType {
    GOOGLE,
    TWITTER,
    LOCAL, // Admin local login + virtual users
    GUEST  // 임시 게스트 (OAuth 미사용 — 합성 이메일 guest-...@guest.local)
}
