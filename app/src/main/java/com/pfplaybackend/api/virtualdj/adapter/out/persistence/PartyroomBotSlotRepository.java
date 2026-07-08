package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomBotSlotData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartyroomBotSlotRepository
        extends JpaRepository<PartyroomBotSlotData, PartyroomBotSlotData.PartyroomBotSlotId> {

    List<PartyroomBotSlotData> findByPartyroomId(Long partyroomId);

    void deleteByPartyroomIdAndBotUserId(Long partyroomId, Long botUserId);

    void deleteByPartyroomId(Long partyroomId);
}
