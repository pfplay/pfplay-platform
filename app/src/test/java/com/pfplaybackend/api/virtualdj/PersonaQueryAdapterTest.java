package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPersonaAssignmentRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PersonaQueryAdapter;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualPersonaRepository;
import com.pfplaybackend.api.virtualdj.domain.entity.data.BotPersonaAssignmentData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualPersonaData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PersonaQueryAdapter} 단위 테스트 — 2회 lookup 매핑 사슬을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PersonaQueryAdapterTest {

    @Mock private BotPersonaAssignmentRepository assignmentRepository;
    @Mock private VirtualPersonaRepository personaRepository;

    @InjectMocks private PersonaQueryAdapter adapter;

    private static final long BOT_USER_ID = 7001L;
    private static final long PERSONA_ID = 42L;

    @Test
    @DisplayName("매핑 + 페르소나 존재 → 지시문 반환")
    void mapped_returnsInstruction() {
        BotPersonaAssignmentData assignment = BotPersonaAssignmentData.create(BOT_USER_ID, PERSONA_ID);
        VirtualPersonaData persona = VirtualPersonaData.builder()
                .name("차분한 디제이")
                .instruction("차분하고 음악에 진심인 톤으로 말한다.")
                .active(true)
                .build();
        when(assignmentRepository.findById(BOT_USER_ID)).thenReturn(Optional.of(assignment));
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));

        String instruction = adapter.instructionOf(BOT_USER_ID);

        assertThat(instruction).isEqualTo("차분하고 음악에 진심인 톤으로 말한다.");
    }

    @Test
    @DisplayName("비활성 페르소나여도 기존 매핑 보존 → 지시문 반환")
    void mappedButInactivePersona_stillReturnsInstruction() {
        BotPersonaAssignmentData assignment = BotPersonaAssignmentData.create(BOT_USER_ID, PERSONA_ID);
        VirtualPersonaData persona = VirtualPersonaData.builder()
                .name("은퇴한 페르소나")
                .instruction("여전히 살아있는 지시문")
                .active(false)
                .build();
        when(assignmentRepository.findById(BOT_USER_ID)).thenReturn(Optional.of(assignment));
        when(personaRepository.findById(PERSONA_ID)).thenReturn(Optional.of(persona));

        String instruction = adapter.instructionOf(BOT_USER_ID);

        assertThat(instruction).isEqualTo("여전히 살아있는 지시문");
    }

    @Test
    @DisplayName("매핑 row 없음 → null, 페르소나 조회 안 함")
    void noAssignment_returnsNull() {
        when(assignmentRepository.findById(BOT_USER_ID)).thenReturn(Optional.empty());

        String instruction = adapter.instructionOf(BOT_USER_ID);

        assertThat(instruction).isNull();
        verify(personaRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }
}
