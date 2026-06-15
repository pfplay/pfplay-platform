package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.party.application.dto.dj.DjWithProfileDto;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 로비 쿼리가 프로필 없는 참조 유저(가상 DJ 봇 아바타 생성 실패/더미/phantom)에 대해
 * NPE→500 로 죽지 않음을 실제 빈(Testcontainers MySQL+Redis)으로 끝까지 구동해 잠근다. (#291)
 *
 * <p>핵심: {@link UserProfileQueryPort} 를 MockBean 으로 가리지 않고 <b>실제</b>
 * {@code UserProfileQueryService.getUsersProfileSetting} + 실제 repository 를 태운다.
 * DJ 의 userId 에 대응하는 {@code user_profile} 행을 일부러 만들지 않으면,
 * fix 이전엔 맵에서 silent drop → {@code profileSettingDto.avatarIconUri()} NPE,
 * fix 이후엔 placeholder 로 채워져 graceful 응답한다.
 */
@Transactional
class LobbyNullProfileGuardIT extends AbstractIntegrationTest {

    @Autowired
    private PartyroomQueryService partyroomQueryService;

    @AfterEach
    void clearAuthContext() {
        ThreadLocalContext.clearContext();
    }

    private PartyroomId persistFullActivePartyroom(UserId hostId) {
        PartyroomData partyroom = PartyroomData.create(
                "null-profile 가드 파티룸", "로비 NPE 방어 잠금용",
                LinkDomain.of("null-profile-" + hostId.getUid()),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL, hostId);
        entityManager.persist(partyroom);
        entityManager.flush();
        PartyroomId partyroomId = new PartyroomId(partyroom.getId());
        entityManager.persist(PartyroomPlaybackData.createFor(partyroomId));
        entityManager.persist(DjQueueData.createFor(partyroomId));
        return partyroomId;
    }

    private CrewData persistActiveCrew(PartyroomId partyroomId, UserId userId) {
        CrewData crew = CrewData.create(partyroomId, userId, GradeType.CLUBBER, null);
        entityManager.persist(crew);
        flushAndClear();
        return crew;
    }

    private void persistDjRow(PartyroomId partyroomId, long crewId, long playlistId, int orderNumber) {
        DjData dj = DjData.create(partyroomId, new PlaylistId(playlistId), new CrewId(crewId), orderNumber);
        entityManager.persist(dj);
        flushAndClear();
    }

    @Test
    @DisplayName("getDjs — DJ 의 user_profile 이 없어도 NPE 없이 placeholder 로 응답한다 (#291)")
    void getDjsWithProfilelessDjDoesNotThrow() {
        // given: 호스트는 임의, DJ 의 userId(9999...)는 user_profile 행을 만들지 않음 (프로필 없는 봇/더미/phantom 모사)
        UserId hostId = new UserId(1001L);
        UserId profilelessDjUserId = new UserId(99990001L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId);
        CrewData djCrew = persistActiveCrew(partyroomId, profilelessDjUserId);
        persistDjRow(partyroomId, djCrew.getId(), 4242L, 1);

        // when / then: 로비 DJ 큐 조회가 NPE 로 죽지 않는다
        List<DjWithProfileDto> djs = assertDoesNotThrowAndGet(partyroomId);

        // and: 프로필 없는 DJ 도 결과에 포함되며 placeholder(non-null) 로 채워진다
        assertThat(djs).hasSize(1);
        DjWithProfileDto dj = djs.get(0);
        assertThat(dj.crewId()).isEqualTo(djCrew.getId());
        assertThat(dj.nickname()).isNotNull();   // placeholder "" — fix 이전엔 여기서 NPE 났음
    }

    private List<DjWithProfileDto> assertDoesNotThrowAndGet(PartyroomId partyroomId) {
        final List<DjWithProfileDto>[] holder = new List[1];
        assertThatCode(() -> holder[0] = partyroomQueryService.getDjs(partyroomId))
                .doesNotThrowAnyException();
        return holder[0];
    }
}
