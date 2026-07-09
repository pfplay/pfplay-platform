package com.pfplaybackend.api.virtualcrew.adapter.in.web.payload;

import com.pfplaybackend.api.virtualcrew.application.service.VirtualSongPackService;

import java.util.List;

/**
 * 송 팩 상세 응답 — 트랙 목록 포함.
 */
public record SongPackDetailResponse(Long id, String name, String description, List<Track> tracks) {

    public record Track(Long trackId, String name, String linkId, String duration, String thumbnailImage) {}

    public static SongPackDetailResponse from(VirtualSongPackService.PackDetail detail) {
        List<Track> tracks = detail.tracks().stream()
                .map(t -> new Track(t.trackId(), t.name(), t.linkId(), t.duration(), t.thumbnailImage()))
                .toList();
        return new SongPackDetailResponse(detail.id(), detail.name(), detail.description(), tracks);
    }
}
