package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 봇↔페르소나 일괄 해제 요청 — 선택 봇들({@code botIds})의 매핑 해제. */
public record UnassignPersonaRequest(
        @NotEmpty List<Long> botIds) {}
