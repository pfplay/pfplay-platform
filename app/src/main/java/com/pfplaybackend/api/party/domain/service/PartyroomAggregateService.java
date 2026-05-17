package com.pfplaybackend.api.party.domain.service;

import com.pfplaybackend.api.common.domain.event.DomainEvent;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartyroomAggregateService {

    private final PartyroomAggregatePort aggregatePort;

    /**
     * DJ큐에서 제거 + 순서 재배치 (atomic operation)
     */
    public void removeDjFromQueue(PartyroomId partyroomId, CrewId crewId) {
        List<DjData> queuedDjs = aggregatePort.findDjsOrdered(partyroomId);
        List<DjData> toDelete = queuedDjs.stream()
                .filter(dj -> dj.getCrewId().equals(crewId))
                .toList();
        List<DjData> remaining = queuedDjs.stream()
                .filter(dj -> !dj.getCrewId().equals(crewId))
                .toList();

        aggregatePort.removeDjs(toDelete);

        int order = 1;
        for (DjData dj : remaining) {
            dj.updateOrderNumber(order++);
        }
        aggregatePort.saveDjs(remaining);
    }

    /**
     * DJ큐 로테이션 (1번→마지막, 나머지 -1)
     */
    public List<DjData> rotateDjQueue(PartyroomId partyroomId) {
        List<DjData> queuedDjs = aggregatePort.findDjsOrdered(partyroomId);
        int totalElements = queuedDjs.size();
        queuedDjs.forEach(dj -> {
            if (dj.getOrderNumber() == 1) {
                dj.updateOrderNumber(totalElements);
            } else {
                dj.updateOrderNumber(dj.getOrderNumber() - 1);
            }
        });
        aggregatePort.saveDjs(queuedDjs);
        return queuedDjs;
    }

    /**
     * 재생 비활성화 + 전체 DJ 일괄 삭제
     * @return 엔티티에서 수집된 도메인 이벤트 목록
     */
    public List<DomainEvent> deactivatePlayback(PartyroomId partyroomId) {
        PartyroomPlaybackData playbackState = aggregatePort.findPlaybackState(partyroomId);
        playbackState.deactivate();
        aggregatePort.savePlaybackState(playbackState);
        List<DjData> queuedDjs = aggregatePort.findDjsOrdered(partyroomId);
        log.warn("[deactivatePlayback] partyroomId={} - removing {} DJ(s) from queue",
                partyroomId.getId(), queuedDjs.size());
        aggregatePort.removeDjs(queuedDjs);
        return playbackState.pollDomainEvents();
    }

    /**
     * DJ큐에 활성 DJ가 존재하는지 확인
     */
    public boolean hasQueuedDjs(PartyroomId partyroomId) {
        return aggregatePort.hasDjs(partyroomId);
    }
}
