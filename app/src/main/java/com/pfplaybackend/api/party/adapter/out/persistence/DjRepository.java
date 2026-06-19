package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DjRepository extends JpaRepository<DjData, Long> {
    List<DjData> findByPartyroomIdOrderByOrderNumberAsc(PartyroomId partyroomId);
    Optional<DjData> findByPartyroomIdAndCrewId(PartyroomId partyroomId, CrewId crewId);
    boolean existsByPartyroomId(PartyroomId partyroomId);
    boolean existsByPartyroomIdAndCrewId(PartyroomId partyroomId, CrewId crewId);

    /**
     * orphan DJ(비활성 크루의 dj row)를 가진 ACTIVE 룸 목록 — reconcile cron Sweep A (#308).
     * DjData↔CrewData JPA 연관이 없어 cross-join + 임베디드 .id 경로로 비교.
     */
    @Query("SELECT DISTINCT d.partyroomId FROM DjData d, CrewData c, PartyroomData pr " +
           "WHERE c.id = d.crewId.id AND c.isActive = false " +
           "AND pr.partyroomId.id = d.partyroomId.id " +
           "AND pr.status = com.pfplaybackend.api.party.domain.enums.PartyroomStatus.ACTIVE")
    List<PartyroomId> findPartyroomIdsWithInactiveCrewDj();
}
