package com.pfplaybackend.api.common.config.security.enums;

public enum ProviderType {
    GOOGLE,
    TWITTER,
    ADMIN,  // DEPRECATED — to be removed in this PR (Task 13) after call-site migration
    LOCAL   // Admin local login + virtual users
}
