package com.pfplaybackend.api.user.application.dto.result;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

public record MyInfoResult(
        @Schema(example = "550e8400-e29b-41d4-a716-446655440000") String uid,
        @Schema(example = "user@gmail.com") String email,
        @Schema(example = "FM") AuthorityTier authorityTier,
        @Schema(example = "true") boolean profileUpdated,
        @Schema(example = "2025-01-15") LocalDate registrationDate
) {
    /**
     * Build a {@link MyInfoResult} from the user account row plus tier /
     * profile-updated flags resolved separately. Tier and profileUpdated
     * moved to {@code MemberData}/{@code GuestData} in the V4 IAM refactor.
     */
    public static MyInfoResult from(UserAccountData user, AuthorityTier tier, boolean profileUpdated) {
        return new MyInfoResult(
                user.getUserId().getUid().toString(),
                user.getEmail(),
                tier,
                profileUpdated,
                user.getCreatedAt().toLocalDate()
        );
    }
}
