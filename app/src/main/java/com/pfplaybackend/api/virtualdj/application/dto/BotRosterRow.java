package com.pfplaybackend.api.virtualdj.application.dto;

/** 봇 로스터 1행 — 봇 신원 + 현재 아바타 + 배치 룸(없으면 null). */
public record BotRosterRow(
        Long userId,
        String nickname,
        String avatarBodyUri,
        String avatarIconUri,
        Long placementPartyroomId,
        String placementPartyroomTitle
) {}
