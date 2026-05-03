package com.pfplaybackend.api.partyview.application.dto.result;

import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.partyview.application.dto.CrewSetupDto;
import com.pfplaybackend.api.partyview.application.dto.DisplayDto;

import java.util.List;

public record PartyroomSetupResult(
    StageType stageType,
    List<CrewSetupDto> crews,
    DisplayDto display
) {}
