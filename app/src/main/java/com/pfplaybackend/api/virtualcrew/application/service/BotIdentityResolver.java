package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPoolQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * crewId → 봇(is_dummy) 여부 판별. 채팅 관찰 파이프라인의 loop guard 로 쓰인다.
 *
 * <p>봇이 자신(또는 다른 봇)의 발화에 다시 반응하는 무한 루프를 막기 위해, 트리거 게이트가
 * 발화자 crew 가 봇인지 먼저 확인한다.
 *
 * <p>캐싱 없음(spec §11.6 — simple first). 매 호출마다 crew row 1건 + bot 필터 1회.
 */
@Service
@RequiredArgsConstructor
public class BotIdentityResolver {

    private final CrewRepository crewRepository;
    private final BotPoolQueryRepository botPoolQueryRepository;

    /**
     * 주어진 crewId 의 crew member 가 봇 계정인지 반환한다.
     *  - crew row 없음 → false
     *  - crew 의 userId 가 봇 계정(is_dummy=true, withdrawn_at IS NULL) → true, 아니면 false
     */
    public boolean isBotCrew(long crewId) {
        Optional<CrewData> crew = crewRepository.findById(crewId);
        if (crew.isEmpty()) {
            return false;
        }
        Long userId = crew.get().getUserId().getUid();
        return !botPoolQueryRepository.filterBotUserIds(List.of(userId)).isEmpty();
    }
}
