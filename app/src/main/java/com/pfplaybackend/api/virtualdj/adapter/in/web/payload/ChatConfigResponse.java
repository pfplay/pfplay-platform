package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import com.pfplaybackend.api.virtualdj.application.dto.ChatConfigView;

/** 가상 DJ 채팅/자가갱신 설정 조회 응답. */
public record ChatConfigResponse(
        boolean chatEnabled,
        boolean selfUpdateEnabled,
        int probabilityPercent,
        int cooldownSeconds,
        int contextSize,
        int outputMaxTokens) {

    public static ChatConfigResponse from(ChatConfigView view) {
        return new ChatConfigResponse(
                view.chatEnabled(),
                view.selfUpdateEnabled(),
                view.probabilityPercent(),
                view.cooldownSeconds(),
                view.contextSize(),
                view.outputMaxTokens());
    }
}
