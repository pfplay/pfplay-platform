package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.dto.partyroom.ActivePartyroomDto;
import com.pfplaybackend.api.party.application.port.out.PartyroomQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cluster A PR-1 특성화(characterization) 회귀 잠금.
 *
 * presence 경로는 사용자의 권위 있는(authoritative) active 룸을 STOMP subscribe 타이밍이 아니라
 * 기존 {@link PartyroomQueryPort#getActivePartyroomByUserId(UserId)} 계약으로 resolve 한다.
 * 신규 포트 메서드를 추가하지 않고 이 기존 계약을 잠가, 후속 presence 리팩터링이
 * 룸-resolve 진실 원천을 조용히 깨뜨리지 못하게 한다.
 *
 * 프로덕션 코드 변경 없음 — 작성·배선만으로 GREEN 인 것이 정상(이것이 곧 잠금).
 */
@Transactional
class ClusterAPresenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PartyroomQueryPort partyroomQueryPort;

    private PartyroomId persistFullActivePartyroom(UserId hostId) {
        // 1) PartyroomData (root) — IDENTITY 생성 PK 확보
        PartyroomData partyroom = PartyroomData.create(
                "Cluster A 파티룸", "presence resolve 잠금용 파티룸",
                LinkDomain.of("cluster-a-presence-" + hostId.getUid()),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL, hostId);
        entityManager.persist(partyroom);
        entityManager.flush();
        PartyroomId partyroomId = new PartyroomId(partyroom.getId());

        // 2) getActivePartyroomByUserId QueryDSL 은 PARTYROOM_PLAYBACK / DJ_QUEUE 와
        //    inner join 하므로 두 상태 행이 반드시 존재해야 한다.
        entityManager.persist(PartyroomPlaybackData.createFor(partyroomId));
        entityManager.persist(DjQueueData.createFor(partyroomId));

        return partyroomId;
    }

    @Test
    @DisplayName("getActivePartyroomByUserId — active crew 사용자는 그 파티룸의 ActivePartyroomDto 를 반환하고 dto.id() 는 PartyroomId 로 매핑된다")
    void activeCrewUserResolvesActivePartyroom() {
        // given
        UserId hostId = new UserId(900L);
        UserId crewUserId = new UserId(901L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);

        CrewData activeCrew = CrewData.create(
                partyroomId, crewUserId, GradeType.CLUBBER, null);
        entityManager.persist(activeCrew);
        flushAndClear();

        // when
        Optional<ActivePartyroomDto> result =
                partyroomQueryPort.getActivePartyroomByUserId(crewUserId);

        // then
        assertThat(result).isPresent();
        ActivePartyroomDto dto = result.get();
        assertThat(dto.id()).isEqualTo(partyroomId.getId());
        // 후속 presence resolve 가 사용하는 DTO → 도메인 값 매핑이 유효함을 잠근다.
        assertThat(new PartyroomId(dto.id())).isEqualTo(partyroomId);
        assertThat(dto.crewId()).isEqualTo(activeCrew.getId());
    }

    @Test
    @DisplayName("getActivePartyroomByUserId — 비active crew 사용자는 Optional.empty() 를 반환한다")
    void inactiveCrewUserResolvesEmpty() {
        // given
        UserId hostId = new UserId(910L);
        UserId crewUserId = new UserId(911L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);

        CrewData inactiveCrew = CrewData.create(
                partyroomId, crewUserId, GradeType.CLUBBER, null);
        inactiveCrew.deactivatePresence();
        entityManager.persist(inactiveCrew);
        flushAndClear();

        // when
        Optional<ActivePartyroomDto> result =
                partyroomQueryPort.getActivePartyroomByUserId(crewUserId);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getActivePartyroomByUserId — crew 행이 없는 사용자는 Optional.empty() 를 반환한다")
    void absentCrewUserResolvesEmpty() {
        // given
        UserId hostId = new UserId(920L);
        UserId unknownUserId = new UserId(921L);
        persistFullActivePartyroom(hostId);
        flushAndClear();

        // when
        Optional<ActivePartyroomDto> result =
                partyroomQueryPort.getActivePartyroomByUserId(unknownUserId);

        // then
        assertThat(result).isEmpty();
    }
}
