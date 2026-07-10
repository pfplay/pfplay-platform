package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPersonaAssignmentRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualPersonaRepository;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualPersonaData;
import com.pfplaybackend.api.virtualcrew.domain.exception.VirtualCrewException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 가상 DJ 페르소나(VirtualPersona) CRUD 서비스.
 *
 * <p>페르소나는 LLM 채팅 응답의 성격·톤·장르 성향을 정의하는 system 지시문 묶음이다.
 * 어드민이 관리하며, 봇 계정에 1:1 매핑된다(매핑 로직은 별도 Task 1.6~1.7 에서 구현).
 */
@Service
@RequiredArgsConstructor
public class VirtualPersonaService {

    private final VirtualPersonaRepository repository;
    private final BotPersonaAssignmentRepository assignmentRepository;

    // ── 서비스 내부 타입 ──

    /**
     * 페르소나 목록 아이템 — 활성 여부 포함.
     */
    public record PersonaListItem(Long id, String name, boolean active) {}

    /**
     * 페르소나 상세 — instruction 포함.
     */
    public record PersonaDetail(Long id, String name, String instruction, boolean active) {}

    // ── 읽기 ──

    @Transactional(readOnly = true)
    public List<PersonaListItem> list() {
        return repository.findAll().stream()
                .map(p -> new PersonaListItem(p.getId(), p.getName(), p.isActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PersonaDetail get(Long id) {
        VirtualPersonaData persona = repository.findById(id)
                .orElseThrow(() -> ExceptionCreator.create(VirtualCrewException.PERSONA_NOT_FOUND));
        return new PersonaDetail(persona.getId(), persona.getName(), persona.getInstruction(), persona.isActive());
    }

    // ── 쓰기 ──

    @Transactional
    public Long create(String name, String instruction) {
        if (repository.existsByName(name)) {
            throw ExceptionCreator.create(VirtualCrewException.PERSONA_DUPLICATE_NAME);
        }
        VirtualPersonaData saved = repository.save(VirtualPersonaData.create(name, instruction));
        return saved.getId();
    }

    @Transactional
    public void update(Long id, String name, String instruction, boolean active) {
        if (repository.existsByNameAndIdNot(name, id)) {
            throw ExceptionCreator.create(VirtualCrewException.PERSONA_DUPLICATE_NAME);
        }
        VirtualPersonaData persona = repository.findById(id)
                .orElseThrow(() -> ExceptionCreator.create(VirtualCrewException.PERSONA_NOT_FOUND));
        persona.update(name, instruction);
        persona.setActive(active);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw ExceptionCreator.create(VirtualCrewException.PERSONA_NOT_FOUND);
        }
        // Delete guard: 봇에 매핑된 페르소나는 삭제 금지(먼저 매핑 해제 필요).
        if (assignmentRepository.existsByPersonaId(id)) {
            throw ExceptionCreator.create(VirtualCrewException.PERSONA_IN_USE);
        }
        repository.deleteById(id);
    }
}
