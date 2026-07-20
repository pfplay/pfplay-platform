package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PartyroomQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class CrewRepositoryAtomicToggleIT extends AbstractIntegrationTest {

    @Autowired private CrewRepository crewRepository;
    @Autowired private PartyroomQueryPort partyroomQueryPort;

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

    // ── bulkDeactivateByPartyroomId ───────────────

    @Test
    @DisplayName("bulkDeactivateByPartyroomId — N active crew → 모두 inactive 전환")
    void bulk_deactivate_normal() {
        long roomId = 5001L;
        seedActiveCrew(roomId, 5001L);
        seedActiveCrew(roomId, 5002L);
        seedActiveCrew(roomId, 5003L);
        seedInactiveCrew(roomId, 5004L);   // 이미 inactive — 영향 없어야 함

        int affected = crewRepository.bulkDeactivateByPartyroomId(
                new PartyroomId(roomId), LocalDateTime.now());

        assertThat(affected).isEqualTo(3);   // active 3건만
        assertThat(crewRepository.findByPartyroomIdAndIsActiveTrue(new PartyroomId(roomId))).isEmpty();
    }

    @Test
    @DisplayName("bulkDeactivateByPartyroomId — 다른 룸의 crew는 영향 없음")
    void bulk_deactivate_room_isolation() {
        long roomA = 5010L;
        long roomB = 5011L;
        seedActiveCrew(roomA, 5010L);
        seedActiveCrew(roomB, 5011L);

        int affected = crewRepository.bulkDeactivateByPartyroomId(
                new PartyroomId(roomA), LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
        // roomB의 crew는 여전히 active
        assertThat(crewRepository.findByPartyroomIdAndIsActiveTrue(new PartyroomId(roomB))).hasSize(1);
    }

    @Test
    @DisplayName("bulkDeactivateByPartyroomId — 빈 룸 → 0 affected")
    void bulk_deactivate_empty() {
        int affected = crewRepository.bulkDeactivateByPartyroomId(
                new PartyroomId(99_999L), LocalDateTime.now());
        assertThat(affected).isZero();
    }

    // ── #349 uk_crew_active_user: "유저당 활성 방 1개" DB 불변식 ────────────

    @Test
    @DisplayName("#349 같은 유저를 두 방에 동시 active → uk_crew_active_user 위반")
    void active_user_unique_rejects_second_active_room() {
        long uid = 6001L;
        seedActiveCrew(6001L, uid);   // 방 A 활성

        // 다른 방 B 에 같은 유저를 또 active INSERT → 생성컬럼 active_user_id 충돌
        CrewData second = CrewData.create(new PartyroomId(6002L), new UserId(uid),
                GradeType.LISTENER, CountryCode.of("KR"), LocalDateTime.now());
        assertThatThrownBy(() -> crewRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("#349 유저의 비활성 crew 는 여러 방에 공존 가능 (active_user_id NULL 중복 허용)")
    void inactive_crews_coexist_across_rooms() {
        long uid = 6010L;
        seedInactiveCrew(6011L, uid);
        seedInactiveCrew(6012L, uid);   // 위반 없이 둘 다 저장돼야 함

        assertThat(crewRepository.findByPartyroomIdAndUserId(new PartyroomId(6011L), new UserId(uid))).isPresent();
        assertThat(crewRepository.findByPartyroomIdAndUserId(new PartyroomId(6012L), new UserId(uid))).isPresent();
    }

    @Test
    @DisplayName("#349 기존 방 비활성화 후 다른 방 활성화는 성공 (collapse 후 재입장 경로)")
    void reactivate_in_other_room_after_deactivate_succeeds() {
        long uid = 6020L;
        CrewData a = seedActiveCrew(6021L, uid);   // 방 A 활성
        crewRepository.deactivateCrew(a.getPartyroomId(), a.getUserId(), LocalDateTime.now()); // A 비활성 → active_user_id=NULL

        CrewData b = CrewData.create(new PartyroomId(6022L), new UserId(uid),
                GradeType.LISTENER, CountryCode.of("KR"), LocalDateTime.now());
        CrewData savedB = crewRepository.saveAndFlush(b);

        assertThat(savedB.isActive()).isTrue();
    }

    // ── #349 getActivePartyroomByUserId de-masking (LEFT JOIN + coalesce) ──────

    @Test
    @DisplayName("#349 하위행(playback/djqueue) 없이도 활성 crew 는 조회됨 (masking 제거) + boolean coalesce")
    void active_room_resolved_even_without_playback_or_djqueue_rows() {
        long roomId = 6030L;
        long uid = 6030L;
        seedActiveCrew(roomId, uid);   // CREW 만 존재 — PARTYROOM_PLAYBACK / DJ_QUEUE 행 없음

        Optional<ActivePartyroomDto> result = partyroomQueryPort.getActivePartyroomByUserId(new UserId(uid));

        // INNER JOIN 시절이면 하위행 부재로 빈 Optional(masking) → 이제는 crew 기준으로 해석돼 present.
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(roomId);
        // coalesce(false) 방어 — NULL 언박싱 NPE 없이 결정적 기본값.
        assertThat(result.get().playbackActivated()).isFalse();
        assertThat(result.get().queueClosed()).isFalse();
    }
}
