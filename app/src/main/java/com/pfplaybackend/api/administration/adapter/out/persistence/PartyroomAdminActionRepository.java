package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.PartyroomAdminActionData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartyroomAdminActionRepository extends JpaRepository<PartyroomAdminActionData, Long> {

    /** B-2 detail의 recentAdminActions 용 — 시간 역순 LIMIT 10. */
    List<PartyroomAdminActionData> findTop10ByPartyroomIdOrderByOccurredAtDesc(Long partyroomId);
}
