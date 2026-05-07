package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateAdministratorRequest {

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotBlank
    @Size(max = 64)
    private String nickname;

    /**
     * Boxed Boolean intentional: with @NoArgsConstructor + Jackson, an omitted
     * field deserializes to null. The service treats null as the default-on
     * (true) per Decision 8 — a primitive boolean would silently default to
     * false and bypass the spec'd default.
     */
    private Boolean includeMemberProfile;
}
