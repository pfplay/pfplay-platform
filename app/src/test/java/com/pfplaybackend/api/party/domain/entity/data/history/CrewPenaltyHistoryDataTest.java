package com.pfplaybackend.api.party.domain.entity.data.history;

import com.pfplaybackend.api.party.domain.enums.PenaltyType;
import com.pfplaybackend.api.party.domain.enums.PunisherType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CrewPenaltyHistoryDataTest {

    @Test
    @DisplayName("release — released 상태와 releaserCrewId가 설정된다")
    void releaseSetsReleasedStateAndReleaserInfo() {
        // given
        CrewPenaltyHistoryData penalty = CrewPenaltyHistoryData.builder()
                .partyroomId(new PartyroomId(1L))
                .punisherCrewId(new CrewId(10L))
                .punishedCrewId(new CrewId(20L))
                .penaltyType(PenaltyType.PERMANENT_EXPULSION)
                .penaltyDate(LocalDateTime.now())
                .released(false)
                .build();

        CrewId releaserCrewId = new CrewId(10L);

        // when
        penalty.release(releaserCrewId);

        // then
        assertThat(penalty.isReleased()).isTrue();
        assertThat(penalty.getReleasedByCrewId()).isEqualTo(releaserCrewId);
        assertThat(penalty.getReleaseDate()).isNotNull();
    }

    @Test
    @DisplayName("release — 이미 released된 상태에서 다시 release하면 상태가 유지된다")
    void releaseIdempotentWhenAlreadyReleased() {
        // given
        CrewPenaltyHistoryData penalty = CrewPenaltyHistoryData.builder()
                .partyroomId(new PartyroomId(1L))
                .punisherCrewId(new CrewId(10L))
                .punishedCrewId(new CrewId(20L))
                .penaltyType(PenaltyType.PERMANENT_EXPULSION)
                .penaltyDate(LocalDateTime.now())
                .released(false)
                .build();

        penalty.release(new CrewId(10L));

        // when
        CrewId newReleaserId = new CrewId(30L);
        penalty.release(newReleaserId);

        // then
        assertThat(penalty.isReleased()).isTrue();
        assertThat(penalty.getReleasedByCrewId()).isEqualTo(newReleaserId);
    }

    @Test
    @DisplayName("releaseByAdmin: released=true + releasedByCrewId=null + releaseDate set")
    void releaseByAdmin_marks_admin_release() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 28, 12, 0);
        CrewPenaltyHistoryData history = CrewPenaltyHistoryData.builder()
                .partyroomId(new PartyroomId(1L))
                .punishedCrewId(new CrewId(10L))
                .punisherCrewId(null)
                .punisherType(PunisherType.ADMIN)
                .penaltyType(PenaltyType.PERMANENT_EXPULSION)
                .penaltyReason("admin reason")
                .penaltyDate(LocalDateTime.of(2026, 4, 28, 11, 0))
                .released(false)
                .build();

        history.releaseByAdmin(now);

        assertThat(history.isReleased()).isTrue();
        assertThat(history.getReleasedByCrewId()).isNull();
        assertThat(history.getReleaseDate()).isEqualTo(now);
    }
}
