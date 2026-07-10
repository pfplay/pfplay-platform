package com.pfplaybackend.api.party.adapter.out.persistence.custom;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.dto.partyroom.PartyroomWithCrewDto;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PartyroomRepositoryCustom {
    Optional<ActivePartyroomDto> getActivePartyroomByUserId(UserId userId);
    List<PartyroomWithCrewDto> getCrewDataByPartyroomId();
    List<PlaybackData> getRecentPlaybackHistory(PartyroomId partyroomId);
    List<PartyroomData> findAllUnusedPartyroomDataByDay(int days);

    /**
     * 음악 재생 구간 재구성용 playback 조회.
     * created_at ∈ [from, now) 인 모든 row + from 직전 최신 1건(straddle, 윈도우 open 시점에
     * 재생 중이었을 수 있는 트랙)을 created_at ASC 로 반환.
     */
    List<PlaybackData> findPlaybackForInterval(PartyroomId partyroomId, LocalDateTime from, LocalDateTime now);

    /**
     * 디제잉 이력 페이지네이션 조회. created_at DESC 고정 정렬.
     */
    Page<PlaybackData> findPlaybackHistory(PartyroomId partyroomId, Pageable pageable);
}
