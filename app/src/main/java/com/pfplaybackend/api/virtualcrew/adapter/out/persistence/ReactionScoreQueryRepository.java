package com.pfplaybackend.api.virtualcrew.adapter.out.persistence;

import com.pfplaybackend.api.virtualcrew.application.dto.LinkReactionScore;
import java.time.LocalDateTime;
import java.util.List;

/**
 * P3-B 자가갱신용 cross-BC 반응 읽기 — playback / playback_aggregation / playback_reaction_history 를
 * 가로질러 봇 자기 plays 의 반응을 집계한다. ActiveDjSnapshotQueryRepository 와 동일한 virtualcrew-adapter
 * cross-BC 패턴. ArchUnit: "Orchestrator" 미포함 + party *AggregatePort/*MessagePublisher 미의존(QueryDSL 엔티티 조회만)이라 통과.
 */
public interface ReactionScoreQueryRepository {

    /** watermark 이후 봇 plays 에 달린 반응 row 수(비용 게이트). botUserIds 비면 0. since==null 이면 전체 기간. */
    long countReactionsSince(List<Long> botUserIds, long partyroomId, LocalDateTime since);

    /** 한 봇의 plays 를 link_id 로 group 한 반응 집계(score 입력). */
    List<LinkReactionScore> aggregateByLink(long botUserId, long partyroomId);
}
