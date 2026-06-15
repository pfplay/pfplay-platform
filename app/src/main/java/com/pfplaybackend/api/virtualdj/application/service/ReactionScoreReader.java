package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.ReactionScoreQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * P3-B 자가갱신용 반응 점수 읽기 서비스.
 *
 * <p>{@link ReactionScoreQueryRepository} 결과에 weight 를 적용해 오름차순 정렬한 {@link ScoredLink} 목록을
 * 반환한다. 가장 점수가 낮은(반응이 좋지 않은) 곡이 먼저 오므로 교체 우선순위 그대로 쓸 수 있다.
 *
 * <p>ActiveDjSnapshotService 와 동일한 "thin wrapper" 패턴.
 */
@Service
@RequiredArgsConstructor
public class ReactionScoreReader {

    private final ReactionScoreQueryRepository repo;
    private final SelfUpdateConfig config;

    /**
     * @param linkId 곡 링크 식별자
     * @param score  가중 반응 점수 ({@code w_react*(like-dislike) + w_grab*grab})
     */
    public record ScoredLink(String linkId, double score) {}

    /**
     * watermark 이후 봇 plays 에 달린 반응 row 수(비용 게이트용).
     *
     * @param botUserIds 봇 userId 목록 (비면 즉시 0)
     * @param partyroomId 파티룸 ID
     * @param since watermark (null 이면 전체 기간)
     */
    public long countNewReactions(List<Long> botUserIds, long partyroomId, LocalDateTime since) {
        return repo.countReactionsSince(botUserIds, partyroomId, since);
    }

    /**
     * 봇의 plays 를 link_id 로 집계한 후 weighted score 기준 오름차순 정렬해 반환한다.
     *
     * <p>score = {@code w_react*(likeSum - dislikeSum) + w_grab*grabSum}
     * 낮은 점수(반응 나쁜 곡)가 앞에 오므로 교체 후보 선정에 바로 활용 가능.
     */
    @Transactional(readOnly = true)
    public List<ScoredLink> scoredAscending(UserId botUserId, PartyroomId partyroomId) {
        double wReact = config.weightReaction();
        double wGrab  = config.weightGrab();

        return repo.aggregateByLink(botUserId.getUid(), partyroomId.getId())
                .stream()
                .map(lrs -> {
                    double score = wReact * (lrs.likeSum() - lrs.dislikeSum()) + wGrab * lrs.grabSum();
                    return new ScoredLink(lrs.linkId(), score);
                })
                .sorted(Comparator.comparingDouble(ScoredLink::score))
                .toList();
    }
}
