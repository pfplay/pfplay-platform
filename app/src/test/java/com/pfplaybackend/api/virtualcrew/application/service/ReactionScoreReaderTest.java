package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.ReactionScoreQueryRepository;
import com.pfplaybackend.api.virtualcrew.application.dto.LinkReactionScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class ReactionScoreReaderTest {

    private final ReactionScoreQueryRepository repo = mock(ReactionScoreQueryRepository.class);
    private final SelfUpdateConfig config = mock(SelfUpdateConfig.class);
    private ReactionScoreReader reader;

    @BeforeEach
    void setUp() {
        reader = new ReactionScoreReader(repo, config);
    }

    @Test
    @DisplayName("scoredAscending — w_react=1.0, w_grab=2.0 → linkB(-2.0) < linkA(4.0) 오름차순")
    void scoredAscending_weightsAndSortAscending() {
        // w_react=1.0, w_grab=2.0 ; linkA(3,1,1)→4.0 ; linkB(0,2,0)→-2.0 ; ascending=[linkB,linkA]
        when(config.weightReaction()).thenReturn(1.0);
        when(config.weightGrab()).thenReturn(2.0);
        when(repo.aggregateByLink(7L, 99L)).thenReturn(List.of(
                new LinkReactionScore("linkA", 3, 1, 1),
                new LinkReactionScore("linkB", 0, 2, 0)));

        List<ReactionScoreReader.ScoredLink> result = reader.scoredAscending(new UserId(7L), new PartyroomId(99L));

        assertThat(result).extracting(ReactionScoreReader.ScoredLink::linkId)
                .containsExactly("linkB", "linkA");
        assertThat(result.get(0).score()).isCloseTo(-2.0, within(1e-9));
        assertThat(result.get(1).score()).isCloseTo(4.0, within(1e-9));
    }

    @Test
    @DisplayName("countNewReactions — repo 에 위임")
    void countNewReactions_delegates() {
        when(repo.countReactionsSince(List.of(7L), 99L, null)).thenReturn(5L);

        long count = reader.countNewReactions(List.of(7L), 99L, null);

        assertThat(count).isEqualTo(5L);
        verify(repo).countReactionsSince(List.of(7L), 99L, null);
    }
}
