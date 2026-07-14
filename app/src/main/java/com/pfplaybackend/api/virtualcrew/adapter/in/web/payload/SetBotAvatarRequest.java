package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import jakarta.validation.constraints.NotBlank;

/** 봇 개별 아바타 설정 요청. */
public record SetBotAvatarRequest(@NotBlank String avatarBodyUri) {}
