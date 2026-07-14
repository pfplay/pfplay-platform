package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomBotSlotRepository;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomBotSlotData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 봇 slot 재확보(reclaim) + 배정(assign).
 *
 * <p>방 안에서 각 DJ 봇은 {@code [0, djBotCount)} 범위의 안정적인 slot index 를 점유하고, 트랙은
 * {@code TrackDistribution} 파티션으로 그 slot 에 매핑된다. 새 봇을 투입할 때:
 * <ol>
 *   <li>방의 저장된 slot 을 전부 로드한다.</li>
 *   <li>더 이상 live 하지 않은(=크루에서 빠진) 봇의 slot 은 정리(delete)한다 — §6 cleanup.</li>
 *   <li>여전히 live 한 봇들이 점유한 slot 집합을 계산한다.</li>
 *   <li>{@code [0, djBotCount)} 중 점유되지 않은 <b>최소</b> index 를 골라 새 봇에 배정·저장하고 반환한다.</li>
 * </ol>
 *
 * <p>slot row 는 배정 시 insert, 회수 시 delete 되는 불변 레코드다(index 를 in-place mutate 하지 않는다).
 */
@Component
@RequiredArgsConstructor
public class BotSlotAssigner {

    private final PartyroomBotSlotRepository slots;

    /**
     * 비-live 봇 slot 정리 후, 새 봇에 최소 free slot 을 배정한다.
     *
     * <p><b>전제조건:</b> {@code newBotUserId} 는 이 방에 아직 slot 을 갖지 않은 진짜 신규 봇이어야 한다
     * (호출자 책임).
     *
     * @param room             파티룸
     * @param liveDjBotUserIds 여전히 크루(DJ)로 살아있는 봇 user id 목록
     * @param newBotUserId     새로 slot 을 배정받을 봇 user id
     * @param djBotCount          방의 DJ 봇 목표 수(= slot 공간 크기)
     * @return 새 봇에 배정된 slot index
     */
    @Transactional
    public int reclaimAndAssign(PartyroomId room,
                                List<Long> liveDjBotUserIds,
                                Long newBotUserId,
                                int djBotCount) {
        Long roomId = room.getId();
        List<PartyroomBotSlotData> stored = slots.findByPartyroomId(roomId);

        Set<Long> live = new HashSet<>(liveDjBotUserIds);

        // 2) 비-live 봇 slot 정리
        for (PartyroomBotSlotData slot : stored) {
            if (!live.contains(slot.getBotUserId())) {
                slots.deleteByPartyroomIdAndBotUserId(roomId, slot.getBotUserId());
            }
        }

        // 3) live 봇이 점유한 slot 집합
        Set<Integer> occupied = new HashSet<>();
        for (PartyroomBotSlotData slot : stored) {
            if (live.contains(slot.getBotUserId())) {
                occupied.add(slot.getSlotIndex());
            }
        }

        // 4) [0, djBotCount) 중 최소 free index 선택
        int chosen = 0;
        while (chosen < djBotCount && occupied.contains(chosen)) {
            chosen++;
        }
        if (chosen >= djBotCount) {
            throw new IllegalStateException(
                    "no free bot slot in [0,%d) for room=%d (occupied=%s)".formatted(djBotCount, roomId, occupied));
        }

        slots.save(PartyroomBotSlotData.create(roomId, newBotUserId, chosen));
        return chosen;
    }

    /**
     * 방의 모든 봇 slot row 를 삭제한다(drain 등 전체 비우기 경로에서 사용).
     *
     * @param room 파티룸
     */
    @Transactional
    public void clearRoom(PartyroomId room) {
        slots.deleteByPartyroomId(room.getId());
    }
}
