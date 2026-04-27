package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AttachMemberProfileRequest {

    @NotBlank
    @Size(max = 64)
    private String nickname;
}
