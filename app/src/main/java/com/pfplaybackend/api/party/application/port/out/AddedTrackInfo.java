package com.pfplaybackend.api.party.application.port.out;

/**
 * Result of a successful GRAB operation: identifies the new playlist entry.
 * Used by analytics to fire `Track Added(source='grab')` from the client side.
 */
public record AddedTrackInfo(Long trackId, Long playlistId) {}
