package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface VirtualSongPackTrackRepository extends JpaRepository<VirtualSongPackTrackData, Long> {

    List<VirtualSongPackTrackData> findBySongPackIdOrderByOrderNumberAsc(Long songPackId);

    /**
     * 송 팩별 트랙 수 집계 — N+1 방지용 group-by 쿼리.
     * 트랙이 없는 팩은 결과에 포함되지 않으므로, 서비스에서 Map 조회 시 기본값 0 사용.
     */
    @Query("select t.songPackId as packId, count(t) as cnt from VirtualSongPackTrackData t group by t.songPackId")
    List<TrackCountView> countGroupBySongPackId();

    interface TrackCountView {
        Long getPackId();
        long getCnt();
    }
}
