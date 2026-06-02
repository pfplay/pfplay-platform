package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPersonaAssignmentRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualPersonaRepository;
import com.pfplaybackend.api.virtualdj.domain.entity.data.BotPersonaAssignmentData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualPersonaData;
import com.pfplaybackend.api.virtualdj.domain.exception.VirtualDjException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 봇↔페르소나 일괄 매핑/해제 서비스.
 *
 * <p>어드민이 봇 로스터에서 봇들을 선택해 페르소나를 일괄 매핑(또는 해제)한다. 매핑은 봇당 1:1
 * (bot_user_id PK)이므로 이미 매핑된 봇은 페르소나만 교체(upsert)한다.
 *
 * <p>일괄 아바타 배분(BotAvatarAdminService#distribute)과 동일하게 {@code filterBotUserIds} 로
 * 실제 봇만 사전 필터해, 비-봇/미존재 id 를 apply 예외로 잡아 격리할 때 공유 트랜잭션이
 * rollback-only 로 마킹돼 성공분까지 롤백되는 문제를 피한다.
 */
@Service
@RequiredArgsConstructor
public class BotPersonaAssignmentService {

    private final VirtualPersonaRepository personaRepository;
    private final BotPersonaAssignmentRepository assignmentRepository;
    private final BotPoolQueryRepository botPoolQueryRepository;

    /**
     * 봇들에 페르소나를 일괄 매핑한다(이미 매핑된 봇은 페르소나 교체). 실제 봇만 적용한 수를 반환한다.
     *
     * @throws com.pfplaybackend.api.common.exception.http.NotFoundException   페르소나 미존재
     * @throws com.pfplaybackend.api.common.exception.http.BadRequestException 비활성 페르소나
     */
    @Transactional
    public int assign(List<Long> botIds, Long personaId) {
        VirtualPersonaData persona = personaRepository.findById(personaId)
                .orElseThrow(() -> ExceptionCreator.create(VirtualDjException.PERSONA_NOT_FOUND));
        if (!persona.isActive()) {
            throw ExceptionCreator.create(VirtualDjException.PERSONA_INACTIVE);
        }

        List<Long> botUserIds = botPoolQueryRepository.filterBotUserIds(botIds);
        Map<Long, BotPersonaAssignmentData> existingByBotUserId =
                assignmentRepository.findByBotUserIdIn(botUserIds).stream()
                        .collect(Collectors.toMap(BotPersonaAssignmentData::getBotUserId, Function.identity()));

        for (Long botUserId : botUserIds) {
            BotPersonaAssignmentData existing = existingByBotUserId.get(botUserId);
            if (existing != null) {
                existing.changePersona(personaId);
            } else {
                assignmentRepository.save(BotPersonaAssignmentData.create(botUserId, personaId));
            }
        }
        return botUserIds.size();
    }

    /**
     * 봇들의 페르소나 매핑을 일괄 해제한다. 실제 봇만 대상으로 한 수를 반환한다.
     */
    @Transactional
    public int unassign(List<Long> botIds) {
        List<Long> botUserIds = botPoolQueryRepository.filterBotUserIds(botIds);
        assignmentRepository.deleteByBotUserIdIn(botUserIds);
        return botUserIds.size();
    }
}
