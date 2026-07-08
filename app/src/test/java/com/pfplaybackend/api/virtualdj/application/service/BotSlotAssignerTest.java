package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomBotSlotRepository;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomBotSlotData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotSlotAssignerTest {

    @Mock
    PartyroomBotSlotRepository slots;

    @InjectMocks
    BotSlotAssigner assigner;

    PartyroomId room = new PartyroomId(1L);

    @Test
    @DisplayName("live DJ 봇 slot 정리 후, 최소 free slot 반환")
    void assigns_lowest_free_after_pruning() {
        // stored: bot10=slot0, bot11=slot1 (bot11 now non-live)
        when(slots.findByPartyroomId(1L)).thenReturn(List.of(slot(10L, 0), slot(11L, 1)));
        List<Long> liveDjBots = List.of(10L);            // bot11 left

        int assigned = assigner.reclaimAndAssign(room, liveDjBots, 12L, /*djBotCount*/3);

        verify(slots).deleteByPartyroomIdAndBotUserId(1L, 11L);  // non-live pruned
        assertThat(assigned).isEqualTo(1);               // slot0 occupied (live), lowest free = 1
    }

    @Test
    @DisplayName("slot0/slot1 이 모두 live 점유면 새 봇은 slot2(최소 free)")
    void assigns_slot2_when_zero_and_one_live_occupied() {
        when(slots.findByPartyroomId(1L)).thenReturn(List.of(slot(10L, 0), slot(11L, 1)));
        List<Long> liveDjBots = List.of(10L, 11L);       // 둘 다 live

        int assigned = assigner.reclaimAndAssign(room, liveDjBots, 12L, /*djBotCount*/3);

        verify(slots, never()).deleteByPartyroomIdAndBotUserId(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyLong());   // 정리 대상 없음
        assertThat(assigned).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 방이면 첫 봇은 slot0 을 배정받고 그 row 를 save 한다")
    void assigns_slot0_on_empty_room_and_saves() {
        when(slots.findByPartyroomId(1L)).thenReturn(List.of());

        int assigned = assigner.reclaimAndAssign(room, List.of(), 20L, /*djBotCount*/3);

        assertThat(assigned).isEqualTo(0);
        ArgumentCaptor<PartyroomBotSlotData> saved = ArgumentCaptor.forClass(PartyroomBotSlotData.class);
        verify(slots).save(saved.capture());
        assertThat(saved.getValue().getPartyroomId()).isEqualTo(1L);
        assertThat(saved.getValue().getBotUserId()).isEqualTo(20L);
        assertThat(saved.getValue().getSlotIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("[0,djBotCount) 가 전부 live 점유면 free slot 없음 → IllegalStateException")
    void throws_when_all_slots_live_occupied() {
        when(slots.findByPartyroomId(1L)).thenReturn(List.of(slot(0L, 0), slot(1L, 1)));
        List<Long> liveDjBots = List.of(0L, 1L);         // djBotCount 만큼 전부 live 점유

        assertThatThrownBy(() -> assigner.reclaimAndAssign(room, liveDjBots, 2L, /*djBotCount*/2))
                .isInstanceOf(IllegalStateException.class);
        verify(slots, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static PartyroomBotSlotData slot(long botId, int idx) {
        return PartyroomBotSlotData.create(1L, botId, idx);
    }
}
