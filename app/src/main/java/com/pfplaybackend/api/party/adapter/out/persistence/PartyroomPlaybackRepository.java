package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PartyroomPlaybackRepository extends JpaRepository<PartyroomPlaybackData, PartyroomId> {

    /**
     * stuck playback(is_activated=1인데 현재 트랙 end_time이 threshold 이전, 또는 current_playback 부재)을 가진
     * ACTIVE 룸 목록 — reconcile cron Sweep B (#308). end_time은 epoch-millis(Long, UTC).
     * "activated인데 current_playback null/orphan"도 stuck으로 포함.
     * NOT EXISTS(정상 진행 playback) = (current null | orphan | end_time<threshold) 와 동치
     * — Hibernate6가 다중 root FROM에서 ON이 형제 root(pp) 참조하는 엔티티 LEFT JOIN을 거부하므로 서브쿼리로.
     */
    @Query("SELECT pp.partyroomId FROM PartyroomPlaybackData pp, PartyroomData pr " +
           "WHERE pr.id = pp.partyroomId.id " +
           "AND pr.status = com.pfplaybackend.api.party.domain.enums.PartyroomStatus.ACTIVE " +
           "AND pp.isActivated = true " +
           "AND NOT EXISTS (SELECT 1 FROM PlaybackData p WHERE p.id = pp.currentPlaybackId.id AND p.endTime >= :threshold)")
    List<PartyroomId> findStuckActivatedPartyroomIds(@Param("threshold") long threshold);
}
