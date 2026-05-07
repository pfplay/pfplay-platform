package com.pfplaybackend.api.user.adapter.in.web.payload.response;

import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class QueryMyInfoResponse {
    private String uid;
    private String email;
    private AuthorityTier authorityTier;
    private boolean profileUpdated;
    private LocalDate registrationDate;

    /**
     * Build a {@link QueryMyInfoResponse} from the user account row plus tier
     * / profile-updated flags resolved separately. Both moved to
     * {@code MemberData}/{@code GuestData} in the V4 IAM refactor.
     */
    public static QueryMyInfoResponse from(UserAccountData user, AuthorityTier tier, boolean profileUpdated) {
        return QueryMyInfoResponse.builder()
                .uid(user.getUserId().getUid().toString())
                .email(user.getEmail())
                .profileUpdated(profileUpdated)
                .registrationDate(user.getCreatedAt().toLocalDate())
                .authorityTier(tier)
                .build();
    }
}
