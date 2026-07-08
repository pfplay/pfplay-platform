package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPersonaAssignmentRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualPersonaRepository;
import com.pfplaybackend.api.virtualdj.domain.entity.data.BotPersonaAssignmentData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualPersonaData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BotPersonaAssignmentService} 단위 테스트 — 봇↔페르소나 일괄 매핑/해제.
 *
 * <p>일괄 배분(distribute) 패턴과 동일하게 {@code filterBotUserIds} 로 실제 봇만 사전 필터하여
 * 비-봇/미존재 id 를 격리한다. 페르소나 존재/활성 가드는 매핑 적용 전에 단언한다.
 */
@ExtendWith(MockitoExtension.class)
class BotPersonaAssignmentServiceTest {

    @Mock
    private VirtualPersonaRepository personaRepository;

    @Mock
    private BotPersonaAssignmentRepository assignmentRepository;

    @Mock
    private BotPoolQueryRepository botPoolQueryRepository;

    @InjectMocks
    private BotPersonaAssignmentService service;

    private static VirtualPersonaData activePersona() {
        return VirtualPersonaData.create("DJ 챌린저", "지시문"); // create() → active=true
    }

    private static VirtualPersonaData inactivePersona() {
        return VirtualPersonaData.builder()
                .name("DJ 힐러").instruction("지시문").active(false).build();
    }

    // ── assign ──

    @Test
    @DisplayName("assign: 활성 페르소나·모두 실제 봇·기존 매핑 없음 → N개 save, N 반환")
    void assign_정상_신규매핑_N개_save() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(activePersona()));
        when(botPoolQueryRepository.filterBotUserIds(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(1L, 2L, 3L));
        when(assignmentRepository.findByBotUserIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of());

        int applied = service.assign(List.of(1L, 2L, 3L), 5L);

        assertThat(applied).isEqualTo(3);
        verify(assignmentRepository, times(3)).save(any(BotPersonaAssignmentData.class));
    }

    @Test
    @DisplayName("assign: 일부 봇은 이미 매핑됨 → 그 row 는 changePersona(신규 save 아님), 그래도 카운트")
    void assign_기존매핑_있으면_changePersona() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(activePersona()));
        when(botPoolQueryRepository.filterBotUserIds(List.of(1L, 2L)))
                .thenReturn(List.of(1L, 2L));
        BotPersonaAssignmentData existing = BotPersonaAssignmentData.create(1L, 9L); // 봇1 은 이미 페르소나 9
        when(assignmentRepository.findByBotUserIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(existing));

        int applied = service.assign(List.of(1L, 2L), 5L);

        assertThat(applied).isEqualTo(2);
        // 봇1: 기존 row 의 페르소나만 교체 (신규 save 아님)
        assertThat(existing.getPersonaId()).isEqualTo(5L);
        // 봇2: 신규 save 1회만
        ArgumentCaptor<BotPersonaAssignmentData> saved = ArgumentCaptor.forClass(BotPersonaAssignmentData.class);
        verify(assignmentRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getBotUserId()).isEqualTo(2L);
        assertThat(saved.getValue().getPersonaId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("assign: 비활성 페르소나 → BadRequestException(PERSONA_INACTIVE), 쓰기 없음")
    void assign_비활성_페르소나_거부() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(inactivePersona()));

        assertThatThrownBy(() -> service.assign(List.of(1L, 2L), 5L))
                .isInstanceOf(BadRequestException.class);

        verify(botPoolQueryRepository, never()).filterBotUserIds(anyList());
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("assign: 존재하지 않는 personaId → NotFoundException(PERSONA_NOT_FOUND), 쓰기 없음")
    void assign_미존재_페르소나_거부() {
        when(personaRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(List.of(1L, 2L), 99L))
                .isInstanceOf(NotFoundException.class);

        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("assign: 비-봇 id 포함 → filterBotUserIds 가 걸러낸 실제 봇에만 적용")
    void assign_비봇_id_필터링() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(activePersona()));
        // 2L 은 비-봇 → 필터 제외
        when(botPoolQueryRepository.filterBotUserIds(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(1L, 3L));
        when(assignmentRepository.findByBotUserIdIn(List.of(1L, 3L)))
                .thenReturn(List.of());

        int applied = service.assign(List.of(1L, 2L, 3L), 5L);

        assertThat(applied).isEqualTo(2);
        verify(assignmentRepository, times(2)).save(any(BotPersonaAssignmentData.class));
    }

    // ── ensurePersonasFor (배치 시 best-effort 자동배정) ──

    private static VirtualPersonaData personaWithId(long id) {
        VirtualPersonaData p = mock(VirtualPersonaData.class);
        when(p.getId()).thenReturn(id);
        return p;
    }

    @Test
    @DisplayName("ensurePersonasFor: 미배정 봇에 활성 페르소나 배정 + 2개 페르소나 라운드로빈 분배")
    void ensurePersonasFor_미배정봇_라운드로빈배정() {
        VirtualPersonaData p1 = personaWithId(101L);
        VirtualPersonaData p2 = personaWithId(102L);
        when(personaRepository.findByActiveTrue()).thenReturn(List.of(p1, p2));
        when(assignmentRepository.findByBotUserIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of()); // 아무도 아직 미배정

        service.ensurePersonasFor(List.of(new UserId(1L), new UserId(2L), new UserId(3L)));

        ArgumentCaptor<BotPersonaAssignmentData> saved = ArgumentCaptor.forClass(BotPersonaAssignmentData.class);
        verify(assignmentRepository, times(3)).save(saved.capture());
        // 라운드로빈: 봇1→p1(101), 봇2→p2(102), 봇3→p1(101).
        assertThat(saved.getAllValues())
                .extracting(BotPersonaAssignmentData::getBotUserId, BotPersonaAssignmentData::getPersonaId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, 101L),
                        org.assertj.core.groups.Tuple.tuple(2L, 102L),
                        org.assertj.core.groups.Tuple.tuple(3L, 101L));
    }

    @Test
    @DisplayName("ensurePersonasFor: 이미 배정된 봇은 건너뛴다(중복 save 없음)")
    void ensurePersonasFor_이미배정_스킵() {
        VirtualPersonaData p1 = personaWithId(101L);
        when(personaRepository.findByActiveTrue()).thenReturn(List.of(p1));
        // 봇1 은 이미 배정됨 → 봇2 만 신규 save.
        when(assignmentRepository.findByBotUserIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(BotPersonaAssignmentData.create(1L, 9L)));

        service.ensurePersonasFor(List.of(new UserId(1L), new UserId(2L)));

        ArgumentCaptor<BotPersonaAssignmentData> saved = ArgumentCaptor.forClass(BotPersonaAssignmentData.class);
        verify(assignmentRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getBotUserId()).isEqualTo(2L);
        assertThat(saved.getValue().getPersonaId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("ensurePersonasFor: 활성 페르소나 없음 → save 없음(WARN 후 그대로 배치, throw 안 함)")
    void ensurePersonasFor_활성페르소나없음_noop() {
        when(personaRepository.findByActiveTrue()).thenReturn(List.of());

        service.ensurePersonasFor(List.of(new UserId(1L), new UserId(2L)));

        verify(assignmentRepository, never()).findByBotUserIdIn(anyList());
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("ensurePersonasFor: 빈 입력 → no-op(페르소나 조회조차 안 함)")
    void ensurePersonasFor_빈입력_noop() {
        service.ensurePersonasFor(List.of());

        verify(personaRepository, never()).findByActiveTrue();
        verify(assignmentRepository, never()).save(any());
    }

    // ── unassign ──

    @Test
    @DisplayName("unassign: 필터된 봇 id 로 deleteByBotUserIdIn 호출, 필터 크기 반환")
    void unassign_필터된봇_삭제() {
        when(botPoolQueryRepository.filterBotUserIds(List.of(1L, 2L, 99L)))
                .thenReturn(List.of(1L, 2L));

        int applied = service.unassign(List.of(1L, 2L, 99L));

        assertThat(applied).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(assignmentRepository).deleteByBotUserIdIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 2L);
    }
}
