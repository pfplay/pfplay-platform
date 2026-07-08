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
     * LEFT JOIN으로 "activated인데 current_playback null"도 stuck으로 포함.
     */
    @Query("SELECT pp.partyroomId FROM PartyroomPlaybackData pp, PartyroomData pr " +
           "LEFT JOIN PlaybackData p ON p.id = pp.currentPlaybackId.id " +
           "WHERE pr.id = pp.partyroomId.id " +
           "AND pr.status = com.pfplaybackend.api.party.domain.enums.PartyroomStatus.ACTIVE " +
           "AND pp.isActivated = true AND (p IS NULL OR p.endTime < :threshold)")
    List<PartyroomId> findStuckActivatedPartyroomIds(@Param("threshold") long threshold);
}
