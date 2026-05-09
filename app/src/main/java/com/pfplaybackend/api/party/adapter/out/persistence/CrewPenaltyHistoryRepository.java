package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.party.domain.entity.data.history.CrewPenaltyHistoryData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrewPenaltyHistoryRepository extends JpaRepository<CrewPenaltyHistoryData, Long> {
    List<CrewPenaltyHistoryData> findAllByPartyroomIdAndReleasedIsFalse(PartyroomId partyroomId);
    Optional<CrewPenaltyHistoryData> findByIdAndPartyroomIdAndReleasedIsFalse(Long id, PartyroomId partyroomId);

    /** [PR 9] partyroom의 최근 페널티 (released 포함). admin detail 응답용. */
    List<CrewPenaltyHistoryData> findTop5ByPartyroomIdOrderByPenaltyDateDesc(PartyroomId partyroomId);
}
