package com.pfplaybackend.api.playlist.domain.policy;

import java.util.List;

/**
 * 재생 커서(last_played_track_id)로부터 "다음에 재생할 위치"를 계산하는 순수 로직.
 * 커서는 트랙 ID 참조이므로 재정렬에 안전하며, 정렬된 트랙 id 목록을 입력으로 받아
 * 커서 다음 위치(순환)를 결정한다. 재생 시작(peekTracksFromCursor)이 이 로직을 사용한다.
 * (조회 화면의 NEXT는 커서 + 현재 순서로부터 클라이언트가 파생한다.)
 */
public final class PlaybackCursorPolicy {

    private PlaybackCursorPolicy() {}

    /**
     * 정렬된 트랙 id 목록에서 커서 "다음" 위치의 시작 인덱스(순환)를 반환한다.
     * 커서가 null이거나 목록에서 찾지 못하면(삭제) 0(맨 앞). 빈 목록이면 0.
     */
    public static int startIndexAfterCursor(List<Long> orderedTrackIds, Long cursor) {
        if (orderedTrackIds == null || orderedTrackIds.isEmpty()) return 0;
        if (cursor == null) return 0;
        int idx = orderedTrackIds.indexOf(cursor);
        if (idx < 0) return 0;
        return (idx + 1) % orderedTrackIds.size();
    }
}
