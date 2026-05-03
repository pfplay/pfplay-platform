package com.pfplaybackend.api.auth.application.dto.result;

import java.time.LocalDateTime;

public record AuthResult(
        String accessToken,
        String tokenType,
        Long expiresIn,
        LocalDateTime issuedAt,
        boolean isNewUser
) {
}
