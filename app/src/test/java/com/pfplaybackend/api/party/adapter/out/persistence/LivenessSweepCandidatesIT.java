package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.application.port.out.LivenessSweepQueryPort;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #356 liveness 스윕 후보 쿼리 회귀 잠금 — 필터 4종(활성·미pending·봇제외·최근입장 유예)을
 * 실제 MySQL(crew × user_account 조인)로 검증한다. 세션 판정(SimpUserRegistry)은 서비스
 * 단위테스트가 커버.
 */
@Transactional
class LivenessSweepCandidatesIT extends AbstractIntegrationTest {

    @Autowired private CrewRepository crewRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private LivenessSweepQueryPort livenessSweepQueryPort;

    private static final LocalDateTime OLD = LocalDateTime.of(2026, 7, 1, 12, 0);

    private UserId seedHuman(long uid) {
        userAccountRepository.save(UserAccountData.createForLocalWithMandatoryChange(
                new UserId(uid), "sweep-" + uid + "@it.local", "h"));
        return new UserId(uid);
    }

    private UserId seedBot(long uid) {
        UserAccountData bot = UserAccountData.createForLocalWithMandatoryChange(
                new UserId(uid), "sweep-bot-" + uid + "@it.local", "h");
        bot.markAsDummy();
        userAccountRepository.save(bot);
        return new UserId(uid);
    }

    private CrewData seedCrew(long roomId, UserId userId, LocalDateTime enteredAt) {
        return crewRepository.saveAndFlush(
                CrewData.create(new PartyroomId(roomId), userId, GradeType.LISTENER, null, enteredAt));
    }

    @Test
    @DisplayName("#356 활성+미pending+비봇+오래된 입장만 후보 — 봇/pending/비활성/최근입장 제외")
    void candidates_filtered_by_liveness_preconditions() {
        LocalDateTime threshold = LocalDateTime.of(2026, 7, 21, 12, 0);

        // A: 후보 — 사람, 활성, pending 없음, 오래된 입장
        UserId ghost = seedHuman(9101L);
        CrewData ghostCrew = seedCrew(9101L, ghost, OLD);

        // B: 봇 — 제외 (봇은 WS 세션이 없어 미제외 시 전 봇이 스윕 대상)
        UserId bot = seedBot(9102L);
        seedCrew(9102L, bot, OLD);

        // C: pending 진행 중 — 제외 (기존 grace/reconcile 관할)
        UserId pendingUser = seedHuman(9103L);
        CrewData pendingCrew = seedCrew(9103L, pendingUser, OLD);
        pendingCrew.markPending(OLD.plusDays(1));
        crewRepository.saveAndFlush(pendingCrew);

        // D: 최근 입장 — 제외 (WS 연결 수립 전 오탐 방지)
        UserId recent = seedHuman(9104L);
        seedCrew(9104L, recent, threshold.plusMinutes(1));

        // E: 비활성 — 제외
        UserId exited = seedHuman(9105L);
        CrewData exitedCrew = seedCrew(9105L, exited, OLD);
        exitedCrew.deactivatePresence(OLD.plusHours(1));
        crewRepository.saveAndFlush(exitedCrew);

        List<CrewData> candidates = livenessSweepQueryPort.findLivenessSweepCandidates(threshold);

        assertThat(candidates)
                .extracting(c -> c.getUserId().getUid())
                .contains(ghost.getUid())
                .doesNotContain(bot.getUid(), pendingUser.getUid(), recent.getUid(), exited.getUid());
        assertThat(candidates).extracting(CrewData::getId).contains(ghostCrew.getId());
    }
}
