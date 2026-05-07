package com.pfplaybackend.api.administration.application.dto.command;

import com.pfplaybackend.api.administration.domain.enums.AdminPenaltyType;

public record AdminApplyPenaltyCommand(
        Long crewId,
        AdminPenaltyType penaltyType,
        String reason
) {}
