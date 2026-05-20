package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E/#223 — DjCommandService.changePlaylist 통합 잠금.
 *
 * <p>대기 중 DJ 의 playlist 변경이 큐 순서(orderNumber)와 다른 DJ row 를 보존하면서
 * DB 에 실제 반영되는지를 Testcontainers MySQL 로 끝까지 구동해 잠근다. idempotent
 * 케이스는 같은 playlist 로의 PATCH 가 최종 row 상태를 바꾸지 않는다는 약속을
 * 검증한다 (SQL UPDATE 발행 여부 자체는 dirty-check 거동 의존이라 비단언).
 *
 * <p>PlaylistQueryPort 는 MockBean 으로 직교 격리: 본 IT 의 관심사는 party 도메인의
 * DJ 큐 mutation/persistence 정합이지 playlist 모듈의 ownership 쿼리 정합이 아님
 * (그것은 PlaylistQueryServiceTest 가 잠금). isOwnedBy=true, isEmptyPlaylist=false.
 */
@Transactional
class DjCommandIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DjCommandService djCommandService;
    @Autowired
    private PartyroomAggregatePort aggregatePort;

    @MockBean
    private PlaylistQueryPort playlistQueryPort;

    @BeforeEach
    void seedPlaylistPort() {
        lenient().when(playlistQueryPort.isOwnedBy(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        lenient().when(playlistQueryPort.isEmptyPlaylist(org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
    }

    @AfterEach
    void clearAuthContext() {
        ThreadLocalContext.clearContext();
    }

    // ───────────────────────── 시드 헬퍼 ─────────────────────────

    private PartyroomId persistFullActivePartyroom(UserId hostId, String linkSuffix) {
        PartyroomData partyroom = PartyroomData.create(
                "changePlaylist IT 파티룸", "E/#223 잠금용",
                LinkDomain.of("e223-" + linkSuffix),
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

    private void seedAuthContext(UserId userId) {
        AuthContext authContext = mock(AuthContext.class);
        when(authContext.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(authContext);
    }

    private Optional<DjData> refetchDj(PartyroomId partyroomId, long crewId) {
        flushAndClear();
        return aggregatePort.findDj(partyroomId, new CrewId(crewId));
    }

    // ───────────────────────── 시나리오 ─────────────────────────

    @Test
    @DisplayName("changePlaylist IT — happy: playlist_id 갱신, order_number 보존")
    void changePlaylistHappyDbUpdated() {
        // given
        UserId hostId = new UserId(8100L);
        UserId userId = new UserId(8101L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId, "happy");
        CrewData crew = persistActiveCrew(partyroomId, userId);
        long crewId = crew.getId();
        persistDjRow(partyroomId, crewId, 4242L, 1);
        seedAuthContext(userId);

        // when
        djCommandService.changePlaylist(partyroomId, new PlaylistId(9999L));

        // then
        Optional<DjData> after = refetchDj(partyroomId, crewId);
        assertThat(after).isPresent();
        assertThat(after.get().getPlaylistId()).isEqualTo(new PlaylistId(9999L));
        assertThat(after.get().getOrderNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("changePlaylist IT — invariant: 본인 변경이 다른 DJ orderNumber·playlistId 무영향")
    void changePlaylistOtherDjOrderPreserved() {
        // given — DJ1(orderNumber=1) + DJ2(orderNumber=2)
        UserId hostId = new UserId(8200L);
        UserId user1 = new UserId(8201L);
        UserId user2 = new UserId(8202L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId, "order");
        long crew1Id = persistActiveCrew(partyroomId, user1).getId();
        long crew2Id = persistActiveCrew(partyroomId, user2).getId();
        persistDjRow(partyroomId, crew1Id, 1001L, 1);
        persistDjRow(partyroomId, crew2Id, 2002L, 2);

        // when — user2 가 본인 playlist 변경
        seedAuthContext(user2);
        djCommandService.changePlaylist(partyroomId, new PlaylistId(7777L));

        // then — DJ1 무변
        Optional<DjData> dj1 = refetchDj(partyroomId, crew1Id);
        assertThat(dj1).isPresent();
        assertThat(dj1.get().getPlaylistId()).isEqualTo(new PlaylistId(1001L));
        assertThat(dj1.get().getOrderNumber()).isEqualTo(1);

        // DJ2 = playlist 변경 + orderNumber 보존
        Optional<DjData> dj2 = refetchDj(partyroomId, crew2Id);
        assertThat(dj2).isPresent();
        assertThat(dj2.get().getPlaylistId()).isEqualTo(new PlaylistId(7777L));
        assertThat(dj2.get().getOrderNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("changePlaylist IT — idempotent: 같은 playlistId → 최종 row 상태 무변")
    void changePlaylistIdempotentNoLogicalChange() {
        // given
        UserId hostId = new UserId(8300L);
        UserId userId = new UserId(8301L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId, "idempotent");
        long crewId = persistActiveCrew(partyroomId, userId).getId();
        persistDjRow(partyroomId, crewId, 5555L, 1);
        seedAuthContext(userId);

        // when — 같은 playlistId 로 PATCH
        djCommandService.changePlaylist(partyroomId, new PlaylistId(5555L));

        // then — 최종 row 상태 동일 (SQL UPDATE 발행 여부는 dirty-check 거동 의존, 비단언)
        Optional<DjData> after = refetchDj(partyroomId, crewId);
        assertThat(after).isPresent();
        assertThat(after.get().getPlaylistId()).isEqualTo(new PlaylistId(5555L));
        assertThat(after.get().getOrderNumber()).isEqualTo(1);
    }
}
