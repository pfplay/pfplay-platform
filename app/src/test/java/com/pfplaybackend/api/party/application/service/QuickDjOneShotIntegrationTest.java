package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.ConflictException;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.DjKind;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.port.PartyroomAggregatePort;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Quick-DJ(#331) — one-shot 회전 트레이스 통합 잠금 (spec §5).
 *
 * <p>PlaylistQueryPort/PlaylistCommandPort 를 모킹하지 않고 실 빈으로 구동한다 —
 * TEMP 플리 준비(prepareOneShotPlaylist)·소유/공백 검증(isOwnedBy/isEmptyPlaylist)·
 * 재생 선곡(peekTracksFromCursor)까지 전부 실경로. 그래서 NORMAL DJ 는 실
 * PLAYLIST+TRACK 시드가 필요하다.
 *
 * <p>재생 완료/스킵 시뮬레이션은 {@link PlaybackCommandService#complete}/{@code skipPlayback}
 * 직접 호출. WS/Redis 릴레이는 단언하지 않고 DB 상태(DJ row·kind·order·playback state)만
 * 단언한다. expiration task 는 Testcontainers Redis 로 스케줄되지만 단언 대상 아님.
 *
 * <p>(e-2)만 tx 밖에서 실행({@code NOT_SUPPORTED}) — 클래스 @Transactional 로는
 * quickEnqueue 의 REQUIRED tx 가 테스트 tx 에 참여해 중간 롤백이 관측되지 않는다.
 * 커밋된 시드/데이터 정리는 DatabaseCleaner 가 다음 테스트 전 자동 수행.
 */
@Transactional
class QuickDjOneShotIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private QuickDjService quickDjService;
    @Autowired
    private DjCommandService djCommandService;
    @Autowired
    private PlaybackCommandService playbackCommandService;
    @Autowired
    private PartyroomAggregatePort aggregatePort;
    @Autowired
    private PlaylistAggregatePort playlistAggregatePort;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearAuthContext() {
        ThreadLocalContext.clearContext();
    }

    // ───────────────────────── 시드 헬퍼 ─────────────────────────

    private PartyroomId persistFullActivePartyroom(UserId hostId, String linkSuffix, int limitMinutes) {
        PartyroomData partyroom = PartyroomData.create(
                "quick-dj IT 파티룸", "#331 잠금용",
                LinkDomain.of("quickdj-" + linkSuffix),
                PlaybackTimeLimit.ofMinutes(limitMinutes),
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

    private Long persistPlaylistWithTrack(UserId owner, String linkId, String duration) {
        PlaylistData playlist = PlaylistData.create(1, "IT 플리", PlaylistType.PLAYLIST, owner);
        entityManager.persist(playlist);
        entityManager.flush();
        TrackData track = TrackData.builder()
                .playlistId(new PlaylistId(playlist.getId()))
                .name("곡-" + linkId).linkId(linkId)
                .duration(Duration.fromString(duration))
                .orderNumber(1)
                .thumbnailImage("https://i.ytimg.com/vi/" + linkId + "/mqdefault.jpg")
                .build();
        entityManager.persist(track);
        flushAndClear();
        return playlist.getId();
    }

    private void seedAuthContext(UserId userId) {
        ThreadLocalContext.setContext(new AuthContext(userId, AuthorityTier.FM));
    }

    private AddTrackCommand song(String name, String linkId, String duration) {
        return new AddTrackCommand(name, linkId, duration,
                "https://i.ytimg.com/vi/" + linkId + "/mqdefault.jpg");
    }

    private PartyroomPlaybackData refetchPlaybackState(PartyroomId partyroomId) {
        flushAndClear();
        return aggregatePort.findPlaybackState(partyroomId);
    }

    // ───────────────────────── 시나리오 ─────────────────────────

    @Test
    @DisplayName("(a) 유일 ONE_SHOT 최초 활성화 — 재생됨 → 완료 시 제거 → deactivate (미재생 삭제 금지 회귀 잠금)")
    void soleOneShotPlaysOnceThenRetiresAndDeactivates() {
        UserId hostId = new UserId(9300L);
        UserId userX = new UserId(9301L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId, "a", 5);
        long crewXId = persistActiveCrew(partyroomId, userX).getId();

        // when — X quickEnqueue: 최초 활성화 경로 → 즉시 재생 시작(미재생 삭제 금지)
        seedAuthContext(userX);
        DjData saved = quickDjService.quickEnqueue(partyroomId, song("곡X", "qdj-a-x", "3:00"));

        // then — DJ row 가 ONE_SHOT 으로 존재하고 X 가 재생 중
        assertThat(saved.getKind()).isEqualTo(DjKind.ONE_SHOT);
        flushAndClear();
        Optional<DjData> row = aggregatePort.findDj(partyroomId, new CrewId(crewXId));
        assertThat(row).isPresent();
        assertThat(row.get().getKind()).isEqualTo(DjKind.ONE_SHOT);
        PartyroomPlaybackData state = aggregatePort.findPlaybackState(partyroomId);
        assertThat(state.isActivated()).isTrue();
        assertThat(state.getCurrentDjCrewId()).isEqualTo(new CrewId(crewXId));

        // when — 재생 완료
        playbackCommandService.complete(partyroomId, userX);

        // then — ONE_SHOT 제거 + 빈 큐 deactivate
        flushAndClear();
        assertThat(aggregatePort.findDj(partyroomId, new CrewId(crewXId))).isEmpty();
        PartyroomPlaybackData after = aggregatePort.findPlaybackState(partyroomId);
        assertThat(after.isActivated()).isFalse();
        assertThat(after.getCurrentDjCrewId()).isNull();
    }

    @Test
    @DisplayName("(b) 혼합 큐 [A(NORMAL,재생중), X(ONE_SHOT), B(NORMAL)] — X 는 1턴 후 소멸, A·B 라운드로빈 보존")
    void mixedQueueOneShotVanishesAfterOneTurnRoundRobinPreserved() {
        UserId hostId = new UserId(9310L);
        UserId userA = new UserId(9311L);
        UserId userX = new UserId(9312L);
        UserId userB = new UserId(9313L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId, "b", 5);
        long crewAId = persistActiveCrew(partyroomId, userA).getId();
        long crewXId = persistActiveCrew(partyroomId, userX).getId();
        long crewBId = persistActiveCrew(partyroomId, userB).getId();
        Long playlistA = persistPlaylistWithTrack(userA, "qdj-b-a", "2:00");
        Long playlistB = persistPlaylistWithTrack(userB, "qdj-b-b", "2:00");

        // given — A(NORMAL, 최초 활성화로 재생 중) → X(ONE_SHOT) → B(NORMAL)
        seedAuthContext(userA);
        djCommandService.enqueueDj(partyroomId, new PlaylistId(playlistA));
        seedAuthContext(userX);
        quickDjService.quickEnqueue(partyroomId, song("곡X", "qdj-b-x", "2:30"));
        seedAuthContext(userB);
        djCommandService.enqueueDj(partyroomId, new PlaylistId(playlistB));

        assertThat(refetchPlaybackState(partyroomId).getCurrentDjCrewId()).isEqualTo(new CrewId(crewAId));

        // when — complete ×1 → 회전으로 X 재생, A 는 tail
        playbackCommandService.complete(partyroomId, userA);

        assertThat(refetchPlaybackState(partyroomId).getCurrentDjCrewId()).isEqualTo(new CrewId(crewXId));
        assertThat(aggregatePort.findDjsOrdered(partyroomId))
                .extracting(dj -> dj.getCrewId().getId(), DjData::getOrderNumber, DjData::getKind)
                .containsExactly(
                        tuple(crewXId, 1, DjKind.ONE_SHOT),
                        tuple(crewBId, 2, DjKind.NORMAL),
                        tuple(crewAId, 3, DjKind.NORMAL));

        // when — complete ×2 → X 는 제거(추가 회전 없이 큐 전진), B 재생
        playbackCommandService.complete(partyroomId, userX);

        assertThat(refetchPlaybackState(partyroomId).getCurrentDjCrewId()).isEqualTo(new CrewId(crewBId));
        assertThat(aggregatePort.findDj(partyroomId, new CrewId(crewXId))).isEmpty();
        assertThat(aggregatePort.findDjsOrdered(partyroomId))
                .extracting(dj -> dj.getCrewId().getId(), DjData::getOrderNumber, DjData::getKind)
                .containsExactly(
                        tuple(crewBId, 1, DjKind.NORMAL),
                        tuple(crewAId, 2, DjKind.NORMAL));

        // when — complete ×3 → NORMAL 라운드로빈 복귀: A 재생
        playbackCommandService.complete(partyroomId, userB);

        assertThat(refetchPlaybackState(partyroomId).getCurrentDjCrewId()).isEqualTo(new CrewId(crewAId));
        assertThat(aggregatePort.findDjsOrdered(partyroomId))
                .extracting(dj -> dj.getCrewId().getId(), DjData::getOrderNumber, DjData::getKind)
                .containsExactly(
                        tuple(crewAId, 1, DjKind.NORMAL),
                        tuple(crewBId, 2, DjKind.NORMAL));
    }

    @Test
    @DisplayName("(c) 현재 DJ dequeue-후-skip — incoming ONE_SHOT 은 미삭제, 그대로 재생 시작")
    void dequeueThenSkipDoesNotDeleteIncomingOneShot() {
        UserId hostId = new UserId(9320L);
        UserId userA = new UserId(9321L);
        UserId userX = new UserId(9322L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId, "c", 5);
        long crewAId = persistActiveCrew(partyroomId, userA).getId();
        long crewXId = persistActiveCrew(partyroomId, userX).getId();
        Long playlistA = persistPlaylistWithTrack(userA, "qdj-c-a", "2:00");

        // given — A(NORMAL) 재생 중 + X(ONE_SHOT) 대기
        seedAuthContext(userA);
        djCommandService.enqueueDj(partyroomId, new PlaylistId(playlistA));
        seedAuthContext(userX);
        quickDjService.quickEnqueue(partyroomId, song("곡X", "qdj-c-x", "2:30"));
        assertThat(refetchPlaybackState(partyroomId).getCurrentDjCrewId()).isEqualTo(new CrewId(crewAId));

        // when — 현재 DJ A 가 본인 dequeue → 내부 skip 경로 (outgoing 이 큐에 없는 quirk 경로)
        seedAuthContext(userA);
        djCommandService.dequeueDj(partyroomId);

        // then — 미재생 ONE_SHOT X 는 삭제되지 않고 그대로 재생 시작
        flushAndClear();
        Optional<DjData> rowX = aggregatePort.findDj(partyroomId, new CrewId(crewXId));
        assertThat(rowX).isPresent();
        assertThat(rowX.get().getKind()).isEqualTo(DjKind.ONE_SHOT);
        PartyroomPlaybackData state = aggregatePort.findPlaybackState(partyroomId);
        assertThat(state.isActivated()).isTrue();
        assertThat(state.getCurrentDjCrewId()).isEqualTo(new CrewId(crewXId));
    }

    @Test
    @DisplayName("(d) 버튼 스킵 — 재생 중 ONE_SHOT 트랙 스킵 → 재등장 없이 제거, 다음 NORMAL 재생")
    void skipDuringOneShotPlaybackRetiresIt() {
        UserId hostId = new UserId(9330L);
        UserId userX = new UserId(9331L);
        UserId userB = new UserId(9332L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId, "d", 5);
        long crewXId = persistActiveCrew(partyroomId, userX).getId();
        long crewBId = persistActiveCrew(partyroomId, userB).getId();
        Long playlistB = persistPlaylistWithTrack(userB, "qdj-d-b", "2:00");

        // given — X(ONE_SHOT) 최초 활성화로 재생 중 + B(NORMAL) 대기
        seedAuthContext(userX);
        quickDjService.quickEnqueue(partyroomId, song("곡X", "qdj-d-x", "2:30"));
        seedAuthContext(userB);
        djCommandService.enqueueDj(partyroomId, new PlaylistId(playlistB));
        assertThat(refetchPlaybackState(partyroomId).getCurrentDjCrewId()).isEqualTo(new CrewId(crewXId));

        // when — 스킵
        playbackCommandService.skipPlayback(partyroomId);

        // then — X 는 재등장 없이 제거, B 재생
        flushAndClear();
        assertThat(aggregatePort.findDj(partyroomId, new CrewId(crewXId))).isEmpty();
        PartyroomPlaybackData state = aggregatePort.findPlaybackState(partyroomId);
        assertThat(state.isActivated()).isTrue();
        assertThat(state.getCurrentDjCrewId()).isEqualTo(new CrewId(crewBId));
        assertThat(aggregatePort.findDjsOrdered(partyroomId))
                .extracting(dj -> dj.getCrewId().getId(), DjData::getOrderNumber, DjData::getKind)
                .containsExactly(tuple(crewBId, 1, DjKind.NORMAL));
    }

    @Test
    @DisplayName("(e-1) 시간한도 사전거부 — limit=3분 방에 3:01 → DJ-007, DJ row 미생성 + TEMP 무변화")
    void trackExceedingTimeLimitIsRejectedBeforeAnyWrite() {
        UserId hostId = new UserId(9340L);
        UserId userX = new UserId(9341L);
        PartyroomId partyroomId = persistFullActivePartyroom(hostId, "e1", 3);
        persistActiveCrew(partyroomId, userX);

        // when & then — 사전거부(DJ-007 BAD_REQUEST)
        seedAuthContext(userX);
        assertThatThrownBy(() -> quickDjService.quickEnqueue(partyroomId, song("곡X", "qdj-e1-x", "3:01")))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("DJ-007"));

        // then — write 이전 거부: DJ row 미생성, TEMP 플리 미생성, 재생 미활성화
        flushAndClear();
        assertThat(aggregatePort.findDjsOrdered(partyroomId)).isEmpty();
        assertThat(playlistAggregatePort.findPlaylistsByOwnerAndType(userX, PlaylistType.TEMP)).isEmpty();
        assertThat(aggregatePort.findPlaybackState(partyroomId).isActivated()).isFalse();
    }

    @Test
    @DisplayName("(e-2) ALREADY_REGISTERED 양자택일 — DJ-001 거부 + TEMP 리셋/삽입까지 전체 롤백 (커밋 경계 관측)")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void alreadyRegisteredRejectionRollsBackTempPreparation() {
        // 시드는 별도 tx 로 커밋 — 본 메서드는 tx 밖이라 quickEnqueue 의 REQUIRED tx 가
        // 독립 커밋/롤백된다(중간상태 관측 가능). 정리는 DatabaseCleaner 위임.
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        UserId hostId = new UserId(9350L);
        UserId userX = new UserId(9351L);
        record Seed(PartyroomId partyroomId, long crewXId) {}
        Seed seed = txTemplate.execute(status -> {
            PartyroomId partyroomId = persistFullActivePartyroom(hostId, "e2", 5);
            long crewXId = persistActiveCrew(partyroomId, userX).getId();
            return new Seed(partyroomId, crewXId);
        });
        PartyroomId partyroomId = seed.partyroomId();

        // given — 곡A 로 quickEnqueue 성공(커밋됨): TEMP=곡A, DJ row 1개(ONE_SHOT)
        seedAuthContext(userX);
        quickDjService.quickEnqueue(partyroomId, song("곡A", "qdj-e2-a", "2:00"));

        List<PlaylistData> tempsBefore = playlistAggregatePort.findPlaylistsByOwnerAndType(userX, PlaylistType.TEMP);
        assertThat(tempsBefore).hasSize(1);
        PlaylistId tempId = new PlaylistId(tempsBefore.get(0).getId());
        assertThat(playlistAggregatePort.findTrackByPlaylistAndLink(tempId, "qdj-e2-a")).isPresent();

        // when & then — 이미 큐에 등록된 상태에서 곡B 재시도 → DJ-001(CONFLICT)
        assertThatThrownBy(() -> quickDjService.quickEnqueue(partyroomId, song("곡B", "qdj-e2-b", "2:00")))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("DJ-001"));

        // then — TEMP 리셋(곡A 삭제)+곡B 삽입이 실제로 롤백되어 곡A 그대로, DJ row 1개 유지
        assertThat(playlistAggregatePort.findTrackByPlaylistAndLink(tempId, "qdj-e2-a")).isPresent();
        assertThat(playlistAggregatePort.findTrackByPlaylistAndLink(tempId, "qdj-e2-b")).isEmpty();
        assertThat(playlistAggregatePort.findPlaylistsByOwnerAndType(userX, PlaylistType.TEMP)).hasSize(1);
        List<DjData> queue = aggregatePort.findDjsOrdered(partyroomId);
        assertThat(queue)
                .extracting(dj -> dj.getCrewId().getId(), DjData::getOrderNumber, DjData::getKind)
                .containsExactly(tuple(seed.crewXId(), 1, DjKind.ONE_SHOT));
    }
}
