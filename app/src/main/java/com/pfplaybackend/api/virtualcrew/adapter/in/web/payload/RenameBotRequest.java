package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 봇 닉네임 변경 요청 — 파티룸 노출명. 비블랭크 + 20자 이하(Nickname 도메인 규칙). */
public record RenameBotRequest(
        @NotBlank @Size(max = 20) String nickname) {}
