package com.pfplaybackend.api.auth.adapter.in.web.payload.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginOAuthResponse {

    @Builder.Default
    private String tokenType = "Cookie";

    private Long expiresIn;

    private LocalDateTime issuedAt;

    /**
     * True iff this OAuth callback created a new {@code UserAccount} row
     * (fresh provider identity), false if an existing user logged in.
     *
     * <p>Frontend uses this to fire {@code User Signed Up} on actual signup
     * only — replacing the previous localStorage UID heuristic which broke
     * across incognito, cache-clear, and multi-device first-login.
     *
     * <p>Amplitude L4 (see {@code amplitude-backend-asks.md}).
     */
    @JsonProperty("isNewUser")
    @Schema(example = "false", description = "True if this call created a new user record (signup), false for returning login.")
    private boolean isNewUser;
}
