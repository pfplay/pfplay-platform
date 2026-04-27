package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class CrewRepositoryAtomicToggleIT extends AbstractIntegrationTest {

    @Autowired private CrewRepository crewRepository;

    private CrewData seedActiveCrew(long roomId, long uid) {
        CrewData crew = CrewData.create(new PartyroomId(roomId), new UserId(uid),
                GradeType.LISTENER, CountryCode.of("KR"), LocalDateTime.now());
        return crewRepository.saveAndFlush(crew);
    }

    private CrewData seedInactiveCrew(long roomId, long uid) {
        CrewData c = seedActiveCrew(roomId, uid);
        c.deactivatePresence(LocalDateTime.now());
        return crewRepository.saveAndFlush(c);
    }

    // ── activateCrew ──────────────────────────────

    @Test
    @DisplayName("activateCrew — inactive row → 1 반환, isActive=true 전이")
    void activate_inactive() {
        CrewData seeded = seedInactiveCrew(4001L, 4001L);

        int affected = crewRepository.activateCrew(seeded.getPartyroomId(), seeded.getUserId(), LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
        Optional<CrewData> reloaded = crewRepository.findByPartyroomIdAndUserId(
                seeded.getPartyroomId(), seeded.getUserId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().isActive()).isTrue();
    }

    @Test
    @DisplayName("activateCrew — 이미 active → 0 반환 (no-op)")
    void activate_already_active() {
        CrewData seeded = seedActiveCrew(4002L, 4002L);

        int affected = crewRepository.activateCrew(seeded.getPartyroomId(), seeded.getUserId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("activateCrew — row 없음 → 0 반환")
    void activate_missing() {
        int affected = crewRepository.activateCrew(new PartyroomId(999_999L),
                new UserId(999_999L), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    // ── deactivateCrew ────────────────────────────

    @Test
    @DisplayName("deactivateCrew — active row → 1 반환, isActive=false 전이")
    void deactivate_active() {
        CrewData seeded = seedActiveCrew(4003L, 4003L);

        int affected = crewRepository.deactivateCrew(seeded.getPartyroomId(), seeded.getUserId(), LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
        Optional<CrewData> reloaded = crewRepository.findByPartyroomIdAndUserId(
                seeded.getPartyroomId(), seeded.getUserId());
        assertThat(reloaded.get().isActive()).isFalse();
        assertThat(reloaded.get().getExitedAt()).isNotNull();
    }

    @Test
    @DisplayName("deactivateCrew — 이미 inactive → 0 반환")
    void deactivate_already_inactive() {
        CrewData seeded = seedInactiveCrew(4004L, 4004L);

        int affected = crewRepository.deactivateCrew(seeded.getPartyroomId(), seeded.getUserId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("deactivateCrew — row 없음 → 0 반환")
    void deactivate_missing() {
        int affected = crewRepository.deactivateCrew(new PartyroomId(999_998L),
                new UserId(999_998L), LocalDateTime.now());

        assertThat(affected).isZero();
    }
}
