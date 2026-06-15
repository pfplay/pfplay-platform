package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.DjQueueRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomPlaybackRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.application.dto.PlaybackTrackDto;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-06-12 prod 빈 룸 유령 재생 사고 재현 (binlog 포렌식으로 역추적한 시나리오).
 *
 * <p>시나리오: 룸A에서 단독 DJ로 재생 중인 사용자가 룸B로 직행 입장(tryEnter → autoExit).
 * exit 경로의 {@code playbackState.deactivate()}는 managed 엔티티의 미flush 변경으로만
 * 존재하는데, 직후 같은 트랜잭션에서 실행되는 {@code activateCrew(룸B)} —
 * {@code @Modifying(clearAutomatically = true)} — 가 영속성 컨텍스트를 clear하면서
 * 그 변경을 조용히 폐기한다(쿼리 스페이스 {crew}≠{partyroom_playback}라 auto-flush도
 * 발동하지 않음). 결과: 빈 룸이 is_activated=1로 영구 고착(유령 재생 + tar pit).
 *
 * <p>이 테스트는 현재 코드에서 FAIL해야 정상(red) — 수정 후 GREEN이 회귀 잠금이 된다.
 */
class PlaybackDeactivateLostOnRoomSwitchIT extends AbstractIntegrationTest {

    @Autowired private PartyroomAccessCommandService accessCommandService;
    @Autowired private DjCommandService djCommandService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired private DjQueueRepository djQueueRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> createdRoomIds = new ArrayList<>();

    /** PA-7.1 프로필 게이트 무력화 — RaceIT/ClusterAPresenceIntegrationTest 동일 패턴. */
    @MockBean
    private UserProfileQueryPort userProfileQueryPort;

    /** 플레이리스트 모듈 경계 — 실 픽스처 대신 포트 모킹 (버그는 party 모듈 JPA 레이어에 있음). */
    @MockBean
    private PlaylistQueryPort playlistQueryPort;
    @MockBean
    private PlaylistCommandPort playlistCommandPort;

    @BeforeEach
    void stubBoundaryPorts() {
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new java.util.HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });
        lenient().when(playlistQueryPort.isOwnedBy(anyLong(), anyLong())).thenReturn(true);
        lenient().when(playlistQueryPort.isEmptyPlaylist(anyLong())).thenReturn(false);
        // 룸 시간제한(5분) 이내 트랙 1곡 — doStart가 정상적으로 재생을 시작하게 한다.
        lenient().when(playlistCommandPort.peekTracksFromCursor(any()))
                .thenReturn(List.of(new PlaybackTrackDto(
                        1L, "testLinkId", "재현용 트랙", "thumb.png",
                        Duration.ofSeconds(180), 1)));
    }

    private long createActiveRoom(long hostUid, String linkSuffix) {
        PartyroomData p = PartyroomData.create(
                "ghost-playback", "intro", LinkDomain.of("link-ghost-" + linkSuffix),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        long id = partyroomRepository.saveAndFlush(p).getId();
        // 실제 룸 생성 플로우(PartyroomCommandService.create)와 동일하게 상태 행 구성
        partyroomPlaybackRepository.save(PartyroomPlaybackData.createFor(new PartyroomId(id)));
        djQueueRepository.save(DjQueueData.createFor(new PartyroomId(id)));
        createdRoomIds.add(id);
        return id;
    }

    @AfterEach
    void cleanupCreatedRooms() {
        ThreadLocalContext.clearContext();
        if (createdRoomIds.isEmpty()) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                            "DELETE FROM playback_aggregation WHERE playback_id IN " +
                            "(SELECT id FROM playback WHERE partyroom_id IN (:ids))")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM playback WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM crew WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM dj WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM dj_queue WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM partyroom_playback WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM partyroom WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
        });
        createdRoomIds.clear();
    }

    @Test
    @DisplayName("재생 중 DJ가 타 룸 직행 입장 → 원 룸 playback은 deactivate되어야 한다 (★ 2026-06-12 prod 유령 재생 재현)")
    void playback_deactivate_must_survive_room_switch() {
        long roomA = createActiveRoom(6001L, "a");
        long roomB = createActiveRoom(6002L, "b");
        UserId user = new UserId(7777L);

        ThreadLocalContext.setContext(new AuthContext(user, AuthorityTier.GT));

        // 1) 룸A 입장 + DJ 등록 → 재생 시작
        accessCommandService.tryEnter(new PartyroomId(roomA), CountryCode.of("KR"));
        djCommandService.enqueueDj(new PartyroomId(roomA), new PlaylistId(99L));

        PartyroomPlaybackData afterStart = loadPlaybackState(roomA);
        assertThat(afterStart.isActivated())
                .as("전제: enqueue 직후 룸A 재생이 시작되어야 한다")
                .isTrue();
        assertThat(afterStart.getCurrentPlaybackId()).isNotNull();

        // 2) 재생 중 룸B로 직행 입장 — autoExit(룸A)가 같은 트랜잭션에서 수행됨
        accessCommandService.tryEnter(new PartyroomId(roomB), CountryCode.of("KR"));

        // 3) 부분 커밋 증명 — 같은 트랜잭션의 벌크 쿼리들은 반영되어 있다
        Number activeCrewInA = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM crew WHERE partyroom_id = :id AND is_active = 1")
                .setParameter("id", roomA).getSingleResult();
        Number djRowsInA = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM dj WHERE partyroom_id = :id")
                .setParameter("id", roomA).getSingleResult();
        assertThat(activeCrewInA.longValue()).as("룸A crew는 비활성화 커밋됨").isZero();
        assertThat(djRowsInA.longValue()).as("룸A DJ 큐는 비워져 커밋됨").isZero();

        // 4) ★ 핵심 단언 — deactivate()가 DB에 반영되어야 한다.
        //    현재 코드에서는 activateCrew(룸B)의 @Modifying(clearAutomatically=true)가
        //    미flush 변경을 폐기해 is_activated=1 유령 재생으로 남는다 (RED 기대).
        PartyroomPlaybackData afterSwitch = loadPlaybackState(roomA);
        assertThat(afterSwitch.isActivated())
                .as("룸A playback deactivate가 커밋되어야 한다 — 유령 재생 금지")
                .isFalse();
        assertThat(afterSwitch.getCurrentPlaybackId())
                .as("deactivate는 current_playback_id를 NULL로 비운다")
                .isNull();
        assertThat(afterSwitch.getCurrentDjCrewId())
                .as("deactivate는 current_dj_crew_id를 NULL로 비운다")
                .isNull();
    }

    /** 영속성 컨텍스트 캐시를 우회한 fresh 로드 — 커밋된 DB 상태만 본다. */
    private PartyroomPlaybackData loadPlaybackState(long roomId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            entityManager.clear();
            return entityManager.find(PartyroomPlaybackData.class, new PartyroomId(roomId));
        });
    }
}
