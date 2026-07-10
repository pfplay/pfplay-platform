package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPersonaAssignmentRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualPersonaRepository;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualPersonaData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VirtualPersonaServiceTest {

    @Mock
    private VirtualPersonaRepository repository;

    @Mock
    private BotPersonaAssignmentRepository assignmentRepository;

    @InjectMocks
    private VirtualPersonaService service;

    // ── create ──

    @Test
    @DisplayName("중복 이름으로 create 시 ConflictException(PERSONA_DUPLICATE_NAME)")
    void create_중복이름_거부() {
        when(repository.existsByName("DJ Luna")).thenReturn(true);

        assertThatThrownBy(() -> service.create("DJ Luna", "지시문"))
                .isInstanceOf(ConflictException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("새 이름으로 create 시 save 호출 후 id 반환")
    void create_새이름_저장후_id_반환() {
        when(repository.existsByName("DJ Nova")).thenReturn(false);
        VirtualPersonaData saved = VirtualPersonaData.create("DJ Nova", "지시문");
        when(repository.save(any())).thenReturn(saved);

        service.create("DJ Nova", "지시문");

        verify(repository).save(any(VirtualPersonaData.class));
    }

    // ── update ──

    @Test
    @DisplayName("존재하지 않는 id로 update 시 NotFoundException(PERSONA_NOT_FOUND)")
    void update_존재하지않는_id_거부() {
        when(repository.existsByNameAndIdNot("DJ Nova", 99L)).thenReturn(false);
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, "DJ Nova", "지시문", true))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("다른 row에서 이미 사용 중인 이름으로 update 시 ConflictException(PERSONA_DUPLICATE_NAME)")
    void update_다른row_이름충돌_거부() {
        when(repository.existsByNameAndIdNot("Taken Name", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, "Taken Name", "지시문", true))
                .isInstanceOf(ConflictException.class);

        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("update 정상 호출 시 name·instruction·active 모두 단일 트랜잭션에서 적용됨")
    void update_단일트랜잭션_name_instruction_active_모두_적용() {
        VirtualPersonaData persona = VirtualPersonaData.create("DJ Luna", "기존 지시문");
        when(repository.existsByNameAndIdNot("DJ Nova", 1L)).thenReturn(false);
        when(repository.findById(1L)).thenReturn(Optional.of(persona));

        service.update(1L, "DJ Nova", "새 지시문", false);

        assertThat(persona.getName()).isEqualTo("DJ Nova");
        assertThat(persona.getInstruction()).isEqualTo("새 지시문");
        assertThat(persona.isActive()).isFalse();
    }

    // ── delete ──

    @Test
    @DisplayName("존재하지 않는 id로 delete 시 NotFoundException(PERSONA_NOT_FOUND)")
    void delete_존재하지않는_id_거부() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(NotFoundException.class);

        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("존재하는 id로 delete 시(매핑 없음) deleteById 호출됨")
    void delete_존재하는_id_정상삭제() {
        when(repository.existsById(1L)).thenReturn(true);
        when(assignmentRepository.existsByPersonaId(1L)).thenReturn(false);

        service.delete(1L);

        verify(repository).deleteById(1L);
    }

    @Test
    @DisplayName("봇에 매핑된 페르소나 delete 시 ConflictException(PERSONA_IN_USE), 삭제 없음")
    void delete_매핑된_페르소나_거부() {
        when(repository.existsById(1L)).thenReturn(true);
        when(assignmentRepository.existsByPersonaId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ConflictException.class);

        verify(repository, never()).deleteById(any());
    }

    // ── list ──

    @Test
    @DisplayName("list 는 전체 엔티티를 PersonaListItem 으로 매핑한다")
    void list_전체_매핑() {
        VirtualPersonaData p1 = VirtualPersonaData.create("DJ Luna", "지시문1");
        VirtualPersonaData p2 = VirtualPersonaData.create("DJ Mars", "지시문2");
        when(repository.findAll()).thenReturn(List.of(p1, p2));

        List<VirtualPersonaService.PersonaListItem> items = service.list();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).name()).isEqualTo("DJ Luna");
        assertThat(items.get(1).name()).isEqualTo("DJ Mars");
    }
}
