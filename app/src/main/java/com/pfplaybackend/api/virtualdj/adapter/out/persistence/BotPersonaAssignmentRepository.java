package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.domain.entity.data.BotPersonaAssignmentData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BotPersonaAssignmentRepository extends JpaRepository<BotPersonaAssignmentData, Long> {

    boolean existsByPersonaId(Long personaId);

    void deleteByBotUserIdIn(Collection<Long> botUserIds);

    List<BotPersonaAssignmentData> findByBotUserIdIn(Collection<Long> botUserIds);
}
