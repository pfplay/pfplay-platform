package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.ActiveDjSnapshotQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 룸의 커밋된 활성 DJ 집합을 사람/봇으로 나눠 제공하는 query 헬퍼 (Chunk 4 reconcile 읽기 경로).
 *
 * <p>오케스트레이터({@code *Orchestrator*})는 도메인 repo 를 직접 주입할 수 없으므로(Chunk 6 ArchUnit)
 * 이 서비스를 거쳐 사람 DJ 수와 제거 대상 봇 목록을 읽는다. 이 클래스는 {@code *Orchestrator*} 가
 * 아니므로 persistence 접근이 허용된다.
 */
@Service
@RequiredArgsConstructor
public class ActiveDjSnapshotService {

    private final ActiveDjSnapshotQueryRepository repository;

    /**
     * 룸의 활성 DJ 스냅샷 — 사람 DJ 수 + 봇 DJ 의 {@link UserId}(가장 최근 합류 봇 먼저).
     */
    @Transactional(readOnly = true)
    public ActiveDjSnapshot snapshot(PartyroomId partyroomId) {
        long humanCount = repository.countActiveHumanDjs(partyroomId);
        List<UserId> bots = repository.findActiveBotDjUserIdsByJoinedDesc(partyroomId);
        return new ActiveDjSnapshot((int) humanCount, bots);
    }

    /**
     * @param humanCount             사람(비-봇) 활성 DJ 수
     * @param botUserIdsByJoinedDesc 봇 활성 DJ 의 userId — 가장 최근 합류 먼저(제거 우선순위 순서)
     */
    public record ActiveDjSnapshot(int humanCount, List<UserId> botUserIdsByJoinedDesc) {
        public int botCount() {
            return botUserIdsByJoinedDesc.size();
        }
    }
}
