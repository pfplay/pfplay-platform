package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.application.port.RoomContextReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link RoomContextReader} 기본 구현.
 *
 * <p>방 제목/소개는 {@link PartyroomQueryService#getPartyroomById}(반환 {@link PartyroomData} —
 * AggregatePort 타입을 노출하지 않는 순수 도메인 엔티티)로 읽고, 현재 재생 곡 제목은
 * {@link PartyroomQueryService#getCurrentPlaybackName}(평이한 {@link String} 반환, 비활성이면 null)로 읽는다.
 *
 * <p>두 메서드 모두 party application query service 만 경유하므로 가상 DJ 패키지의
 * AggregatePort/MessagePublisher 직접 의존 금지 가드를 위반하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RoomContextReaderImpl implements RoomContextReader {

    private final PartyroomQueryService partyroomQueryService;

    @Override
    public RoomContext read(PartyroomId partyroomId) {
        PartyroomData partyroom = partyroomQueryService.getPartyroomById(partyroomId);
        String nowPlayingTitle = partyroomQueryService.getCurrentPlaybackName(partyroomId);
        return new RoomContext(partyroom.getTitle(), partyroom.getIntroduction(), nowPlayingTitle);
    }
}
