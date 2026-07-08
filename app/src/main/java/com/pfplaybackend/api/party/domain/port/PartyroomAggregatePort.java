package com.pfplaybackend.api.party.domain.port;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.*;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PartyroomAggregatePort {

    // ===== Root: PartyroomData =====
    Optional<PartyroomData> findPartyroomById(Long id);
    PartyroomData savePartyroom(PartyroomData partyroom);
    Optional<PartyroomData> findByLinkDomain(LinkDomain linkDomain);
    Optional<PartyroomData> findNonTerminatedHostRoom(UserId userId);
    List<PartyroomData> findAllUnusedPartyroomDataByDay(int days);

    // ===== Crew: CrewData =====
    Optional<CrewData> findCrew(PartyroomId partyroomId, UserId userId);
    Optional<CrewData> findCrewById(Long crewId);
    List<CrewData> findCrewsByIds(Iterable<Long> crewIds);
    CrewData saveCrew(CrewData crew);
    List<CrewData> findActiveCrews(PartyroomId partyroomId);
    long countActiveCrews(PartyroomId partyroomId);
    /** 조건부 atomic toggle. 반환값: 1 (전이 발생) / 0 (미전이). 자세한 시맨틱은 CrewRepository javadoc. */
    int activateCrew(PartyroomId partyroomId, UserId userId, LocalDateTime now);
    int deactivateCrew(PartyroomId partyroomId, UserId userId, LocalDateTime now);
    /** Presence grace: ONLINE → PENDING_EXIT atomic toggle. 1 = transition occurred, 0 = no-op. */
    int markCrewPending(PartyroomId partyroomId, UserId userId, LocalDateTime now);
    /** Presence grace: PENDING_EXIT → ONLINE atomic toggle. 1 = transition occurred, 0 = no-op. */
    int clearCrewPending(PartyroomId partyroomId, UserId userId);
    /** Presence grace reconciliation: rows whose pending_exit_at is older than threshold. */
    List<CrewData> findStalePendingCrews(LocalDateTime threshold);

    // ===== DJ: DjData =====
    List<DjData> findDjsOrdered(PartyroomId partyroomId);
    Optional<DjData> findDjById(Long djId);
    Optional<DjData> findDj(PartyroomId partyroomId, CrewId crewId);
    boolean hasDjs(PartyroomId partyroomId);
    boolean isDjRegistered(PartyroomId partyroomId, CrewId crewId);
    DjData saveDj(DjData dj);
    void saveDjs(List<DjData> djs);
    void removeDjs(List<DjData> djs);

    // ===== Playback State: PartyroomPlaybackData =====
    PartyroomPlaybackData findPlaybackState(PartyroomId partyroomId);
    void savePlaybackState(PartyroomPlaybackData state);

    // ===== DJ Queue State: DjQueueData =====
    DjQueueData findDjQueueState(PartyroomId partyroomId);
    void saveDjQueueState(DjQueueData djQueue);

    // ===== Reconcile cron (#308) =====
    List<PartyroomId> findPartyroomIdsWithInactiveCrewDj();
    List<PartyroomId> findStuckActivatedPartyroomIds(long threshold);
}
