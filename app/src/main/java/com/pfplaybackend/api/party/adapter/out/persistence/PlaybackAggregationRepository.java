package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaybackAggregationRepository extends JpaRepository<PlaybackAggregationData, PlaybackId> {

    /**
     * 좋아요/싫어요/그랩 카운터에 delta 적용. native atomic UPDATE.
     *  - 반환 1: 정상 적용
     *  - 반환 0: row 없음 (호출자 WARN 로그 후 무시)
     * 동시 호출 시 DB row lock으로 직렬화 → lost update 차단.
     * 음수 가드 없음 — like/dislike는 history 기준 delta라 정상 흐름에선 음수 발생 불가.
     * 음수 발생 시 WARN 로그가 history vs counter drift 신호.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PlaybackAggregationData a " +
           "SET a.likeCount = a.likeCount + :deltaLike, " +
           "    a.dislikeCount = a.dislikeCount + :deltaDislike, " +
           "    a.grabCount = a.grabCount + :deltaGrab " +
           "WHERE a.playbackId = :playbackId")
    int applyAggregationDelta(@Param("playbackId") PlaybackId playbackId,
                              @Param("deltaLike") int deltaLike,
                              @Param("deltaDislike") int deltaDislike,
                              @Param("deltaGrab") int deltaGrab);
}
