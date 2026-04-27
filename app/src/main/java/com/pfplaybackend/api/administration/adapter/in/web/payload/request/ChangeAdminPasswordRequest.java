package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChangeAdminPasswordRequest {
    @NotBlank private String currentPassword;
    @NotBlank private String newPassword;
}
