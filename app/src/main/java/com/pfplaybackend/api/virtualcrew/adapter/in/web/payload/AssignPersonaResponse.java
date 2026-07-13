package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

/** 봇↔페르소나 일괄 매핑/해제 결과 — 실제 적용된 봇 수. */
public record AssignPersonaResponse(int applied) {}
