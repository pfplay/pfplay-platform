package com.pfplaybackend.api.virtualcrew.adapter.out.persistence.impl;

import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.ReactionScoreQueryRepository;
import com.pfplaybackend.api.virtualcrew.application.dto.LinkReactionScore;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pfplaybackend.api.party.domain.entity.data.QPlaybackAggregationData.playbackAggregationData;
import static com.pfplaybackend.api.party.domain.entity.data.QPlaybackData.playbackData;
import static com.pfplaybackend.api.party.domain.entity.data.history.QPlaybackReactionHistoryData.playbackReactionHistoryData;

/**
 * P3-B cross-BC 반응 집계 QueryDSL 구현체.
 *
 * <p>ActiveDjSnapshotQueryRepositoryImpl 과 동일한 virtualcrew-adapter cross-BC 패턴.
 * ArchUnit 통과 조건: "Orchestrator" 미포함 + party *AggregatePort/*MessagePublisher 미의존.
 */
@Repository
@RequiredArgsConstructor
public class ReactionScoreQueryRepositoryImpl implements ReactionScoreQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public long countReactionsSince(List<Long> botUserIds, long partyroomId, LocalDateTime since) {
        if (botUserIds.isEmpty()) {
            return 0L;
        }

        var subquery = JPAExpressions
                .select(playbackData.id)
                .from(playbackData)
                .where(
                        playbackData.userId.uid.in(botUserIds),
                        playbackData.partyroomId.id.eq(partyroomId));

        var sincePredicate = since == null ? null
                : playbackReactionHistoryData.createdAt.gt(since);

        Long count = queryFactory
                .select(playbackReactionHistoryData.count())
                .from(playbackReactionHistoryData)
                .where(
                        playbackReactionHistoryData.playbackId.id.in(subquery),
                        sincePredicate)
                .fetchOne();

        return count == null ? 0L : count;
    }

    @Override
    public List<LinkReactionScore> aggregateByLink(long botUserId, long partyroomId) {
        // (1) 봇의 plays: playbackId → linkId
        List<Tuple> plays = queryFactory
                .select(playbackData.id, playbackData.linkId)
                .from(playbackData)
                .where(
                        playbackData.userId.uid.eq(botUserId),
                        playbackData.partyroomId.id.eq(partyroomId))
                .fetch();

        if (plays.isEmpty()) {
            return List.of();
        }

        Map<Long, String> linkIdByPlaybackId = new HashMap<>();
        for (Tuple t : plays) {
            Long pid = t.get(playbackData.id);
            String linkId = t.get(playbackData.linkId);
            if (pid != null && linkId != null) {
                linkIdByPlaybackId.put(pid, linkId);
            }
        }

        // (2) 해당 plays 의 aggregation rows
        List<Long> playbackIds = new ArrayList<>(linkIdByPlaybackId.keySet());
        var aggregations = queryFactory
                .selectFrom(playbackAggregationData)
                .where(playbackAggregationData.playbackId.id.in(playbackIds))
                .fetch();

        // (3) linkId 별 합산: long[3] = {likeSum, dislikeSum, grabSum}
        Map<String, long[]> sums = new HashMap<>();
        for (var agg : aggregations) {
            Long pid = agg.getPlaybackId().getId();
            String linkId = linkIdByPlaybackId.get(pid);
            if (linkId == null) continue;
            long[] s = sums.computeIfAbsent(linkId, k -> new long[3]);
            s[0] += agg.getLikeCount();
            s[1] += agg.getDislikeCount();
            s[2] += agg.getGrabCount();
        }

        // (4) LinkReactionScore 목록 생성
        return sums.entrySet().stream()
                .map(e -> new LinkReactionScore(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }
}
