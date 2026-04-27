package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class PartyroomRepositoryAtomicUpdateIT extends AbstractIntegrationTest {

    @Autowired
    private PartyroomRepository partyroomRepository;

    private PartyroomData createAndSaveActive(long hostUid) {
        PartyroomData p = PartyroomData.create(
                "atomic", "intro", LinkDomain.of("link-" + hostUid),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        return partyroomRepository.saveAndFlush(p);
    }

    private PartyroomData createAndSaveTerminated(long hostUid) {
        PartyroomData p = createAndSaveActive(hostUid);
        p.terminate();
        return partyroomRepository.saveAndFlush(p);
    }

    // ── incrementCrewCount ─────────────────────────────────────────

    @Test
    @DisplayName("incrementCrewCount — ACTIVE 룸에서 +1, lastActivityAt 갱신, 1 affected")
    void increment_active() {
        PartyroomData p = createAndSaveActive(1001L);
        LocalDateTime now = LocalDateTime.now();

        int affected = partyroomRepository.incrementCrewCount(p.getId(), now);

        assertThat(affected).isEqualTo(1);
        PartyroomData reloaded = partyroomRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getCrewCount()).isEqualTo(1);
        assertThat(reloaded.getLastActivityAt()).isEqualToIgnoringNanos(now);
    }

    @Test
    @DisplayName("incrementCrewCount — TERMINATED 룸 거부 (0 affected)")
    void increment_terminated() {
        PartyroomData p = createAndSaveTerminated(1002L);

        int affected = partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("incrementCrewCount — 존재하지 않는 id 거부 (0 affected)")
    void increment_missing() {
        int affected = partyroomRepository.incrementCrewCount(999_999_999L, LocalDateTime.now());
        assertThat(affected).isZero();
    }

    // ── decrementCrewCount ─────────────────────────────────────────

    @Test
    @DisplayName("decrementCrewCount — 정상 -1")
    void decrement_normal() {
        PartyroomData p = createAndSaveActive(1003L);
        partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());
        partyroomRepository.incrementCrewCount(p.getId(), LocalDateTime.now());

        LocalDateTime decrementAt = LocalDateTime.now();
        int affected = partyroomRepository.decrementCrewCount(p.getId(), decrementAt);

        assertThat(affected).isEqualTo(1);
        PartyroomData reloaded = partyroomRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getCrewCount()).isEqualTo(1);
        assertThat(reloaded.getLastActivityAt()).isEqualToIgnoringNanos(decrementAt);
    }

    @Test
    @DisplayName("decrementCrewCount — crewCount=0에서 호출하면 음수 안 되고 0 유지 (음수 가드가 0 유지)")
    void decrement_underflow_guard() {
        PartyroomData p = createAndSaveActive(1004L);

        int affected = partyroomRepository.decrementCrewCount(p.getId(), LocalDateTime.now());

        // MySQL driver may report 0 or 1 here depending on useAffectedRows flag
        // — what matters is the underflow guard kept the count at 0.
        assertThat(affected).isIn(0, 1);
        PartyroomData reloaded = partyroomRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getCrewCount()).isZero();
    }

    @Test
    @DisplayName("decrementCrewCount — TERMINATED 룸 거부")
    void decrement_terminated() {
        PartyroomData p = createAndSaveTerminated(1005L);

        int affected = partyroomRepository.decrementCrewCount(p.getId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    // ── touchLastActivity ──────────────────────────────────────────

    @Test
    @DisplayName("touchLastActivity — ACTIVE 룸 갱신")
    void touch_active() {
        PartyroomData p = createAndSaveActive(1006L);
        LocalDateTime now = LocalDateTime.now();

        int affected = partyroomRepository.touchLastActivity(p.getId(), now);

        assertThat(affected).isEqualTo(1);
        PartyroomData reloaded = partyroomRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isEqualToIgnoringNanos(now);
    }

    @Test
    @DisplayName("touchLastActivity — SUSPENDED 룸 거부 (ACTIVE-only)")
    void touch_suspended() {
        PartyroomData p = createAndSaveActive(1007L);
        p.suspend();
        partyroomRepository.saveAndFlush(p);

        int affected = partyroomRepository.touchLastActivity(p.getId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    @Test
    @DisplayName("touchLastActivity — TERMINATED 룸 거부")
    void touch_terminated() {
        PartyroomData p = createAndSaveTerminated(1008L);

        int affected = partyroomRepository.touchLastActivity(p.getId(), LocalDateTime.now());

        assertThat(affected).isZero();
    }

    // ── findActiveHostRoom (status 시맨틱 변경 회귀) ────────────────

    @Test
    @DisplayName("findActiveHostRoom — TERMINATED 룸은 결과에서 제외")
    void findActiveHostRoom_excludes_terminated() {
        UserId hostId = new UserId(2001L);
        PartyroomData p = createAndSaveActive(2001L);
        p.terminate();
        partyroomRepository.saveAndFlush(p);

        Optional<PartyroomData> result = partyroomRepository.findActiveHostRoom(hostId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findActiveHostRoom — SUSPENDED 룸은 포함 (호스트의 새 룸 생성 차단 의도, spec §6.4(a))")
    void findActiveHostRoom_includes_suspended() {
        UserId hostId = new UserId(2002L);
        PartyroomData p = createAndSaveActive(2002L);
        p.suspend();
        partyroomRepository.saveAndFlush(p);

        Optional<PartyroomData> result = partyroomRepository.findActiveHostRoom(hostId);

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(PartyroomStatus.SUSPENDED);
    }
}
