package com.pfplaybackend.api.administration.adapter.in.web.payload.request;

import com.pfplaybackend.api.administration.application.dto.command.AdminApplyPenaltyCommand;
import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class AdminApplyPenaltyRequest {

    @NotNull(message = "crewId is required.")
    private Long crewId;

    @NotNull(message = "penaltyType is required.")
    private AdminPenaltyType penaltyType;

    @NotBlank(message = "reason is required.")
    @Size(min = 1, max = 255, message = "reason length must be 1..255")
    private String reason;

    public AdminApplyPenaltyCommand toCommand() {
        return new AdminApplyPenaltyCommand(crewId, penaltyType, reason);
    }
}
