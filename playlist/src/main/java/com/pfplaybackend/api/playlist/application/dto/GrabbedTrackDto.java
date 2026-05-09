package com.pfplaybackend.api.playlist.application.dto;

/**
 * Identifies the new track entry created by a successful GRAB.
 * Returned by GrabTrackService so callers can surface trackId / playlistId
 * (e.g. for client-side analytics).
 */
public record GrabbedTrackDto(Long trackId, Long playlistId) {}
