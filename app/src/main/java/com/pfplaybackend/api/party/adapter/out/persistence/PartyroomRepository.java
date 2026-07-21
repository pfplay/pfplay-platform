package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PartyroomRepository extends JpaRepository<PartyroomData, Long>,
        com.pfplaybackend.api.party.adapter.out.persistence.custom.PartyroomRepositoryCustom {

    Optional<PartyroomData> findByLinkDomain(LinkDomain linkDomain);

    /**
     * Host가 보유한 비-종료 룸(ACTIVE 또는 SUSPENDED) 1개 조회.
     * SUSPENDED 룸 보유 중인 호스트도 신규 룸 생성 차단 — spec §6.4(a) 결정.
     */
    @Query("SELECT p FROM PartyroomData p WHERE p.hostId = :userId " +
           "AND p.status <> com.pfplaybackend.api.party.domain.enums.PartyroomStatus.TERMINATED")
    Optional<PartyroomData> findNonTerminatedHostRoom(@Param("userId") UserId userId);


    /**
     * lastActivityAt만 갱신. ACTIVE 룸만 (SUSPENDED/TERMINATED 룸은 거부).
     * Playback 이벤트가 SUSPENDED/TERMINATED 룸 lastActivity 갱신하는 건 의미 없음.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PartyroomData p SET p.lastActivityAt = :now " +
           "WHERE p.id = :id " +
           "AND p.status = com.pfplaybackend.api.party.domain.enums.PartyroomStatus.ACTIVE")
    int touchLastActivity(@Param("id") Long id, @Param("now") LocalDateTime now);

}
