package com.pfplaybackend.api.virtualdj.adapter.out.persistence;

import com.pfplaybackend.api.virtualdj.application.port.PersonaQueryPort;
import com.pfplaybackend.api.virtualdj.domain.entity.data.BotPersonaAssignmentData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualPersonaData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link PersonaQueryPort} 기본 구현 — 2회 lookup(매핑 → 페르소나)으로 지시문을 읽는다.
 *
 * <p>{@code bot_persona_assignment} 의 PK 가 {@code bot_user_id} 이므로 {@code findById(botUserId)}
 * 로 매핑 row 를 찾고, 있으면 그 {@code personaId} 로 {@code virtual_persona} 를 읽어 지시문을 돌려준다.
 * 매핑 row 가 없으면 {@code null}. is_active 여부는 검사하지 않는다(기존 매핑 보존).
 */
@Component
@RequiredArgsConstructor
public class PersonaQueryAdapter implements PersonaQueryPort {

    private final BotPersonaAssignmentRepository assignmentRepository;
    private final VirtualPersonaRepository personaRepository;

    @Override
    public String instructionOf(long botUserId) {
        return assignmentRepository.findById(botUserId)
                .map(BotPersonaAssignmentData::getPersonaId)
                .flatMap(personaRepository::findById)
                .map(VirtualPersonaData::getInstruction)
                .orElse(null);
    }
}
