package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.service.VirtualSongPackService;

/**
 * 송 팩 목록 아이템 응답.
 */
public record SongPackListItemResponse(Long id, String name, String description, long trackCount) {

    public static SongPackListItemResponse from(VirtualSongPackService.PackListItem item) {
        return new SongPackListItemResponse(item.id(), item.name(), item.description(), item.trackCount());
    }
}
