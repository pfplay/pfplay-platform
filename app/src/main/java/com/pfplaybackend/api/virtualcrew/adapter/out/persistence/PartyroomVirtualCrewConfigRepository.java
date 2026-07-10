package com.pfplaybackend.api.virtualcrew.adapter.out.persistence;

import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartyroomVirtualCrewConfigRepository extends JpaRepository<PartyroomVirtualCrewConfigData, Long> {

    Optional<PartyroomVirtualCrewConfigData> findByPartyroomId(Long partyroomId);

    List<PartyroomVirtualCrewConfigData> findByStatus(VirtualCrewStatus status);

    /**
     * 주어진 송 팩을 참조하는 config row 가 하나라도 존재하는지(삭제 가드용).
     * 기존 {@code findAll()} 전체 스캔을 대체 — song_pack_id 인덱스로 즉시 판정한다.
     */
    boolean existsBySongPackId(Long songPackId);
}
