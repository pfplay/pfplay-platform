package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepository;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
import com.pfplaybackend.api.virtualdj.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Chunk 4 오케스트레이터 통합 테스트 — 실 DB(Testcontainers) 위에서 봇 임퍼소네이션 투입/제거를 검증한다.
 *
 * <p>격리: {@link UserProfileQueryPort} mock 으로 assertHasProfile 게이트만 통과시킨다(race IT 와
 * 동일 패턴; 실제 ProfileData 행 구성이 fragile 하기 때문). 그 외 playback/scheduler/이벤트 인프라는
 * 실 빈을 그대로 쓴다 — enqueueDj 의 startPlayback 은 실제로 동작하되 scheduleTask 타이머는 테스트
 * 윈도우 내 발화하지 않아 무해하다. 봇 playlist 소유/비공백 검증도 실 데이터(SongPackApplier 가 봇
 * playlist 에 실제 트랙을 채움)로 통과시킨다.
 */
@Transactional
class VirtualDjOrchestratorIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    @Autowired private VirtualDjOrchestrator orchestrator;
    @Autowired private VirtualUserPoolService poolService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomVirtualDjConfigRepository configRepository;
    @Autowired private VirtualSongPackRepository packRepository;
    @Autowired private VirtualSongPackTrackRepository packTrackRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private TrackRepository trackRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;

    @MockBean private UserProfileQueryPort userProfileQueryPort;

    @BeforeEach
    void seedGatesAndAvatar() {
        // 어떤 userId 든 프로필 보유 상태로 — assertHasProfile 통과.
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });
        // 봇 프로비저닝이 쓰는 기본 아바타 바디 시드 (test 프로파일은 Flyway 비활성).
        // Chunk 3: provision 이 생성 즉시 assignRandomFromCatalog 로 변별 아바타를 부여하므로
        // 이 바디가 published 후보로 노출돼야 한다(standalone, 자체 아이콘 → face 의존 없음).
        if (avatarBodyResourceRepository.findOneAvatarResourceByResourceUri(DEFAULT_BODY_URI) == null) {
            AvatarBodyResourceData body = AvatarBodyResourceData.draft(
                    "ava_body_basic_001", DEFAULT_BODY_URI,
                    "https://example.test/icon_basic_001.png",
                    ObtainmentType.BASIC, 0, false, true, 60, 41, null);
            body.publish(null);
            avatarBodyResourceRepository.save(body);
        }
    }

    @AfterEach
    void clearContext() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("사람0명_MANAGED — 봇이 T(=2)까지 채워진다")
    void 사람0명_MANAGED_봇이_T까지_채워진다() {
        long roomId = seedRoom(5);
        seedManagedConfig(roomId, /*target*/2, /*djBotCount*/2, seedSongPack());
        poolService.provision(3); // ≥ 2 idle bots
        flushAndClear();

        orchestrator.reconcileRoom(new PartyroomId(roomId));
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isEqualTo(2);
    }

    @Test
    @DisplayName("사람1명DJ — 봇은 floor(=1)만 채운다")
    void 사람1명DJ_봇은_floor1만() {
        long roomId = seedRoom(5);
        seedManagedConfig(roomId, /*target*/2, /*floor*/1, seedSongPack());
        poolService.provision(3);
        flushAndClear();

        // 사람 DJ 1명을 활성 DJ 로 등록.
        seedHumanDj(roomId, 9100L);
        flushAndClear();

        orchestrator.reconcileRoom(new PartyroomId(roomId));
        flushAndClear();

        // desiredBot(human=1, T=2, floor=1) = min(2, max(1, 1)) = 1
        assertThat(activeBotDjCount(roomId)).isEqualTo(1);
    }

    @Test
    @DisplayName("MANAGED_송팩없음 — 봇 추가 없음 (SKIP_NO_SONG_PACK)")
    void MANAGED_송팩없음_무변경() {
        long roomId = seedRoom(5);
        // songPackId = null: MANAGED 상태지만 송팩 미설정
        seedManagedConfig(roomId, /*target*/2, /*floor*/1, /*songPackId*/null);
        poolService.provision(3);
        flushAndClear();

        orchestrator.reconcileRoom(new PartyroomId(roomId));
        flushAndClear();

        // 송팩 없으므로 봇이 한 명도 추가되면 안 된다.
        assertThat(activeBotDjCount(roomId)).isZero();
    }

    @Test
    @DisplayName("idempotent — T 충족 후 재호출해도 봇 수 불변·추가 churn 없음")
    void idempotent_재호출_무변경() {
        long roomId = seedRoom(5);
        seedManagedConfig(roomId, 2, 2, seedSongPack());
        poolService.provision(3);
        flushAndClear();

        orchestrator.reconcileRoom(new PartyroomId(roomId));
        flushAndClear();
        assertThat(activeBotDjCount(roomId)).isEqualTo(2);
        long djRowsAfterFirst = djRowCount(roomId);

        // 재호출 — 수렴 상태이므로 enqueue/exit 가 한 번도 일어나면 안 된다.
        orchestrator.reconcileRoom(new PartyroomId(roomId));
        flushAndClear();

        assertThat(activeBotDjCount(roomId)).isEqualTo(2);
        assertThat(djRowCount(roomId)).isEqualTo(djRowsAfterFirst);
    }

    // ── fixtures ──

    private long seedRoom(int playbackTimeLimitMinutes) {
        PartyroomData p = PartyroomData.create(
                "vdj", "intro", LinkDomain.of("link-vdj-" + System.nanoTime()),
                PlaybackTimeLimit.ofMinutes(playbackTimeLimitMinutes), StageType.GENERAL,
                new UserId(8000L));
        long id = partyroomRepository.saveAndFlush(p).getId();
        // enqueueDj 가 findPlaybackState/findDjQueueState 를 orElseThrow 로 읽으므로 부속 행 시드.
        PartyroomId pid = new PartyroomId(id);
        entityManager.persist(PartyroomPlaybackData.createFor(pid));
        entityManager.persist(DjQueueData.createFor(pid));
        entityManager.flush();
        return id;
    }

    private void seedManagedConfig(long roomId, int target, int floor, Long songPackId) {
        PartyroomVirtualDjConfigData cfg = PartyroomVirtualDjConfigData.builder()
                .partyroomId(roomId).status(VirtualDjStatus.MANAGED)
                .targetCount(target).djBotCount(floor).songPackId(songPackId).build();
        configRepository.save(cfg);
    }

    private Long seedSongPack() {
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("Pack", "IT"));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 1, "vidA", "Song A", "2:00", null));
        packTrackRepository.save(VirtualSongPackTrackData.create(pack.getId(), 2, "vidB", "Song B", "3:00", null));
        return pack.getId();
    }

    /** 사람(is_dummy=false) 사용자 1명을 만들어 활성 crew + DJ 로 등록한다. */
    private void seedHumanDj(long roomId, long humanUid) {
        UserId humanId = new UserId(humanUid);
        userAccountRepository.save(UserAccountData.createForSocial(
                humanId, "human-" + humanUid + "@test.local", ProviderType.GOOGLE));

        // 사람 소유의 비-공백 playlist (enqueueDj 의 isOwnedBy/isEmptyPlaylist 통과용).
        PlaylistData playlist = playlistRepository.save(
                PlaylistData.create(1, "Human PL", PlaylistType.PLAYLIST, humanId));
        trackRepository.save(TrackData.builder()
                .playlistId(new PlaylistId(playlist.getId()))
                .name("H1").linkId("hvid1")
                .duration(Duration.fromString("2:00"))
                .orderNumber(1).thumbnailImage(null).build());
        flushAndClear();

        Long playlistId = playlistRepository.findByOwnerIdAndType(humanId, PlaylistType.PLAYLIST).getId();

        // 사람 신원으로 입장 + DJ 등록.
        ThreadLocalContext.setContext(new AuthContext(humanId, AuthorityTier.FM));
        try {
            accessEnterAndEnqueue(roomId, playlistId);
        } finally {
            ThreadLocalContext.clearContext();
        }
    }

    private void accessEnterAndEnqueue(long roomId, Long playlistId) {
        // 명령 서비스 직접 호출 (오케스트레이터가 봇에 대해 하는 것과 동일 경로).
        com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService access =
                getBean(com.pfplaybackend.api.party.application.service.PartyroomAccessCommandService.class);
        com.pfplaybackend.api.party.application.service.DjCommandService dj =
                getBean(com.pfplaybackend.api.party.application.service.DjCommandService.class);
        PartyroomId pid = new PartyroomId(roomId);
        access.tryEnter(pid, null);
        dj.enqueueDj(pid, new PlaylistId(playlistId));
    }

    private <T> T getBean(Class<T> type) {
        return applicationContext.getBean(type);
    }

    @Autowired private org.springframework.context.ApplicationContext applicationContext;

    // ── assertions ──

    /** 룸의 활성 DJ 중 봇(is_dummy=true) 수. */
    private long activeBotDjCount(long roomId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM dj d " +
                        "JOIN crew c ON c.crew_id = d.crew_id " +
                        "JOIN user_account u ON u.user_id = c.user_id " +
                        "WHERE d.partyroom_id = :rid AND c.is_active = 1 AND u.is_dummy = 1")
                .setParameter("rid", roomId)
                .getSingleResult();
        return n.longValue();
    }

    private long djRowCount(long roomId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM dj WHERE partyroom_id = :rid")
                .setParameter("rid", roomId)
                .getSingleResult();
        return n.longValue();
    }
}
