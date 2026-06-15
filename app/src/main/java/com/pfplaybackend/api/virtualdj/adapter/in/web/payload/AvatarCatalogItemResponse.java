package com.pfplaybackend.api.virtualdj.adapter.in.web.payload;

import com.pfplaybackend.api.virtualdj.application.service.BotAvatarAdminService;

/** 아바타 카탈로그 1항목(피커용) 응답. */
public record AvatarCatalogItemResponse(String bodyUri, String name, String thumbnailUri,
                                        boolean combinable, String obtainableType) {
    public static AvatarCatalogItemResponse from(BotAvatarAdminService.CatalogItem c) {
        return new AvatarCatalogItemResponse(c.bodyUri(), c.name(), c.thumbnailUri(),
                c.combinable(), c.obtainableType());
    }
}
