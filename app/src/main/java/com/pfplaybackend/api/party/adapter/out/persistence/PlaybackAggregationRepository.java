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
     *
     * <p>E/#6 (#231) 음수 floor 가드(방어선): {@code GREATEST(0, col + :delta)} 로
     * 어떤 인터리빙/잔존 drift 에서도 카운터가 0 미만으로 떨어지지 않게 한다. 1차 방어는
     * history PESSIMISTIC_WRITE 락이며 이 가드는 defense-in-depth. {@code GREATEST} 는
     * JPQL 표준이 아니므로 MySQL native query 로 작성한다.
     * {@code @Modifying(clearAutomatically=true)} 로 1차 캐시는 비워진다(반환값 계약: affected rows).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE playback_aggregation " +
           "SET like_count = GREATEST(0, like_count + :deltaLike), " +
           "    dislike_count = GREATEST(0, dislike_count + :deltaDislike), " +
           "    grab_count = GREATEST(0, grab_count + :deltaGrab) " +
           // GREATEST 가 native SQL 을 강제 → JPQL 파라미터 바인딩 불가. native query 는
           // @Embeddable PlaybackId 를 직접 바인딩 못 하므로 SpEL :#{#playbackId.id} 로
           // 내부 Long 을 unwrap 해 넘긴다 (이 경로에서 playbackId 는 절대 null 아님).
           "WHERE playback_id = :#{#playbackId.id}",
           nativeQuery = true)
    int applyAggregationDelta(@Param("playbackId") PlaybackId playbackId,
                              @Param("deltaLike") int deltaLike,
                              @Param("deltaDislike") int deltaDislike,
                              @Param("deltaGrab") int deltaGrab);
}
