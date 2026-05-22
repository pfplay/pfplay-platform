package com.pfplaybackend.api.party.adapter.out.persistence;


import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlaybackReactionHistoryRepository extends JpaRepository<PlaybackReactionHistoryData, Long> {
    Optional<PlaybackReactionHistoryData> findByPlaybackIdAndUserId(PlaybackId playbackId, UserId userId);

    /**
     * E/#6 (#231) 리액션 카운터 race 차단용 비관적 쓰기 락.
     *
     * <p>{@code reactToCurrentPlayback} 의 history-read → delta 계산 → aggregation UPDATE
     * 시퀀스를 같은 (user, playback) 단위로 직렬화한다. 이미 존재하는 history 행에 대해
     * {@code SELECT ... FOR UPDATE} 행 락을 잡으므로, 두 동시 트랜잭션이 stale 스냅샷에서
     * 각자 delta 를 계산해 이중 적용하는 drift 가 차단된다.
     *
     * <p>락 순서는 history → aggregation 단방향(데드락-세이프). 첫 반응(행 미존재) 경로는
     * 행 자체가 없어 락 대상이 없으므로 unique 제약 {@code uk_reaction_user_playback} 가
     * 동시 INSERT 의 패자를 거르고, 호출자가 재시도 시 이제 존재하는 행에 락을 잡는다.
     *
     * <p>의도적으로 짧은 락 대기(3s) 를 건다 — MySQL InnoDB 기본 lock-wait(~50s) 동안
     * 같은 (user, playback) 연타가 Tomcat 워커 + DB 커넥션을 묶어 워커 고갈을 유발하므로,
     * 워커 굶주림보다 빠른 실패(클라이언트 재시도) 를 택한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT h FROM PlaybackReactionHistoryData h " +
           "WHERE h.playbackId = :playbackId AND h.userId = :userId")
    Optional<PlaybackReactionHistoryData> findByPlaybackIdAndUserIdForUpdate(
            @Param("playbackId") PlaybackId playbackId,
            @Param("userId") UserId userId);
}
