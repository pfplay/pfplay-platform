package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 봇↔페르소나 일괄 매핑 요청 — 선택 봇들({@code botIds})에 페르소나({@code personaId}) 일괄 적용. */
public record AssignPersonaRequest(
        @NotEmpty List<Long> botIds,
        @NotNull Long personaId) {}
