package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 봇 아바타 일괄 변별 배분 요청 — 선택 봇들에 셋({@code bodyUris})에서 랜덤 1개씩 배분. */
public record DistributeBotAvatarRequest(
        @NotEmpty List<Long> botIds,
        @NotEmpty List<String> bodyUris) {}
