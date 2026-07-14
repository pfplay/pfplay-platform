package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 봇 일괄 제거(탈퇴 soft-delete) 요청 — 선택 봇들({@code botUserIds})을 풀에서 제거. */
public record RemoveBotsRequest(
        @NotEmpty List<Long> botUserIds) {}
