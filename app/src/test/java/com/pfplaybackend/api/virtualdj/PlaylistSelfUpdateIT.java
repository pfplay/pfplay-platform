package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.avatar.adapter.out.persistence.AvatarBodyResourceRepository;
import com.pfplaybackend.api.avatar.domain.entity.data.AvatarBodyResourceData;
import com.pfplaybackend.api.avatar.domain.enums.ObtainmentType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomPlaybackRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackAggregationRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackReactionHistoryRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PlaybackRepository;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackAggregationData;
import com.pfplaybackend.api.party.domain.entity.data.PlaybackData;
import com.pfplaybackend.api.party.domain.entity.data.history.PlaybackReactionHistoryData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepository;
import com.pfplaybackend.api.playlist.application.dto.search.SearchResultDto;
import com.pfplaybackend.api.playlist.application.dto.search.SearchResultRawDto;
import com.pfplaybackend.api.playlist.application.service.search.PytubeSearchService;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualdj.application.port.SongRecommendationProvider;
import com.pfplaybackend.api.virtualdj.application.port.VirtualDjOrchestrator;
import com.pfplaybackend.api.virtualdj.application.service.PlaylistSelfUpdateService;
import com.pfplaybackend.api.virtualdj.application.service.VirtualUserPoolService;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 13 — {@link PlaylistSelfUpdateService#tryUpdateRoom} 풀 DB 통합 테스트(Testcontainers).
 *
 * <p>배포 차단(bean wiring / V29·DDL drift / 실 쿼리 정확성 / 트랜잭션)을 잡는 게이트.
 * LLM({@link SongRecommendationProvider})·Pytube({@link PytubeSearchService})만 @MockBean 으로
 * 시나리오별 제어하고, 그 외 게이트/점수/스왑/송팩 폴백/watermark 는 전부 실 빈 + 실 DB 로 검증한다.
 *
 * <p>봇을 <b>활성 봇 DJ</b> 로 만드는 셋업은 {@link VirtualDjOrchestratorIT} 와 동일하게
 * 오케스트레이터({@code reconcileRoom}) 경로를 그대로 재사용한다 — provision → MANAGED config(+송팩) →
 * reconcile → 봇이 crew + DJ 로 등록되고 playlist 가 송팩 A 트랙으로 채워진다. 그 후 점수/반응을
 * 직접 시드하고 self-update 용 송팩 B 로 config 를 갱신한다.
 *
 * <p>격리: {@link UserProfileQueryPort} 는 race/orchestrator IT 와 동일한 lenient mock 으로
 * assertHasProfile 게이트만 통과시킨다(실 ProfileData 구성이 fragile 하기 때문). 기본 아바타 바디도 시드한다.
 */
@Transactional
class PlaylistSelfUpdateIT extends AbstractIntegrationTest {

    private static final String DEFAULT_BODY_URI =
            "https://firebasestorage.googleapis.com/v0/b/pfplay-firebase.appspot.com/o/ava_basic%2Fava_basic_001.png?alt=media";

    // 봇 playlist 에 들어갈 송팩 A 트랙 linkId
    private static final String LINK_LOW = "vidLow";   // 높은 dislike → 최저점수 → prune 후보
    private static final String LINK_HIGH = "vidHigh"; // 높은 like → 보존
    // 송팩 B(self-update 폴백) 전용 미시도 트랙 linkId
    private static final String LINK_PACK_FALLBACK = "vidPackFallback";
    // LLM/Pytube 가 해소하는 신규 트랙 linkId
    private static final String LINK_NEW = "vidNew";

    @Autowired private PlaylistSelfUpdateService selfUpdateService;
    @Autowired private VirtualDjOrchestrator orchestrator;
    @Autowired private VirtualUserPoolService poolService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomVirtualDjConfigRepository configRepository;
    @Autowired private VirtualSongPackRepository packRepository;
    @Autowired private VirtualSongPackTrackRepository packTrackRepository;
    @Autowired private TrackRepository trackRepository;
    @Autowired private PlaybackRepository playbackRepository;
    @Autowired private PlaybackAggregationRepository playbackAggregationRepository;
    @Autowired private PlaybackReactionHistoryRepository playbackReactionHistoryRepository;
    @Autowired private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired private AvatarBodyResourceRepository avatarBodyResourceRepository;

    @MockBean private SongRecommendationProvider songRecommendationProvider;
    @MockBean private PytubeSearchService pytubeSearchService;
    @MockBean private UserProfileQueryPort userProfileQueryPort;

    @BeforeEach
    void seedGatesAndAvatar() {
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });
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

    // ───────────────────────────── 시나리오 1 ─────────────────────────────

    @Test
    @DisplayName("1. 빈 룸(반응 < K) → LLM 미호출 + playlist 불변 + watermark 미전진 (INV-2)")
    void emptyRoom_noLlm_noChange() {
        Fixture fx = seedActiveBotDjRoom();
        // 반응 history 0건(K=5 미만) — playback/aggregation 은 점수용으로 시드하되 반응은 안 단다.
        seedBotPlays(fx, /*lowDislike*/3, /*highLike*/3, /*reactions*/0);
        flushAndClear();

        List<String> before = playlistLinkIds(fx.botPlaylistId);

        selfUpdateService.tryUpdateRoom(new PartyroomId(fx.roomId));
        flushAndClear();

        verifyNoInteractions(songRecommendationProvider);
        assertThat(playlistLinkIds(fx.botPlaylistId))
                .containsExactlyInAnyOrderElementsOf(before);
        assertThat(reloadConfig(fx.roomId).getLastSelfUpdateAt()).isNull();
    }

    // ───────────────────────────── 시나리오 2 ─────────────────────────────

    @Test
    @DisplayName("2. 반응 ≥ K → 1회 갱신(저점수곡 교체·크기불변·watermark 전진), 재호출은 쿨다운 차단")
    void reactionsAboveK_oneUpdate_thenCooldownBlocks() {
        Fixture fx = seedActiveBotDjRoom();
        seedBotPlays(fx, /*lowDislike*/5, /*highLike*/5, /*reactions*/5);
        flushAndClear();

        int sizeBefore = playlistLinkIds(fx.botPlaylistId).size();

        when(songRecommendationProvider.recommend(any(), anyList(), anyInt()))
                .thenReturn(List.of("new song A"));
        when(pytubeSearchService.searchByWord(eq("new song A"), anyInt()))
                .thenReturn(pytubeHit(LINK_NEW, "New Song A", "3:30"));

        selfUpdateService.tryUpdateRoom(new PartyroomId(fx.roomId));
        flushAndClear();

        Set<String> after = Set.copyOf(playlistLinkIds(fx.botPlaylistId));
        // INV-1: 크기 불변
        assertThat(after).hasSize(sizeBefore);
        // 저점수곡 교체, 신규 트랙 진입
        assertThat(after).doesNotContain(LINK_LOW);
        assertThat(after).contains(LINK_NEW);
        // 고점수곡 보존
        assertThat(after).contains(LINK_HIGH);
        // watermark 전진
        assertThat(reloadConfig(fx.roomId).getLastSelfUpdateAt()).isNotNull();

        // ── 재호출: 쿨다운 미경과로 차단 — recommend 2번째 미호출, playlist 무변 ──
        selfUpdateService.tryUpdateRoom(new PartyroomId(fx.roomId));
        flushAndClear();

        verify(songRecommendationProvider, times(1)).recommend(any(), anyList(), anyInt());
        assertThat(Set.copyOf(playlistLinkIds(fx.botPlaylistId)))
                .containsExactlyInAnyOrderElementsOf(after);
    }

    // ───────────────────────────── 시나리오 3 ─────────────────────────────

    @Test
    @DisplayName("3. LLM 빈 결과 → 송팩 폴백으로 교체, 크기 불변 (INV-1)")
    void llmEmpty_songPackFallback_sizeInvariant() {
        Fixture fx = seedActiveBotDjRoom();
        seedBotPlays(fx, /*lowDislike*/5, /*highLike*/5, /*reactions*/5);
        flushAndClear();

        int sizeBefore = playlistLinkIds(fx.botPlaylistId).size();

        when(songRecommendationProvider.recommend(any(), anyList(), anyInt()))
                .thenReturn(List.of()); // LLM 빈 결과

        selfUpdateService.tryUpdateRoom(new PartyroomId(fx.roomId));
        flushAndClear();

        Set<String> after = Set.copyOf(playlistLinkIds(fx.botPlaylistId));
        // INV-1: 크기 불변
        assertThat(after).hasSize(sizeBefore);
        // 저점수곡이 송팩 B 의 미시도 트랙으로 교체됨
        assertThat(after).doesNotContain(LINK_LOW);
        assertThat(after).contains(LINK_PACK_FALLBACK);
        // LLM 이 빈 결과였으므로 Pytube 해소는 호출되지 않는다.
        verify(pytubeSearchService, never()).searchByWord(any(), anyInt());
    }

    // ───────────────────────────── 시나리오 4 ─────────────────────────────

    @Test
    @DisplayName("4. 현재곡 보호 (INV-3): 최저점수곡이 현재 재생곡이면 prune 되지 않는다")
    void currentTrackProtected_notPruned() {
        Fixture fx = seedActiveBotDjRoom();
        seedBotPlays(fx, /*lowDislike*/5, /*highLike*/5, /*reactions*/5);
        // 최저점수곡(LINK_LOW)을 "현재 재생곡" 으로 만든다:
        // 봇 playback row(linkId=LINK_LOW) 를 만들고 partyroom_playback 을 그걸로 activate.
        PlaybackData nowPlaying = playbackRepository.saveAndFlush(
                PlaybackData.builder()
                        .partyroomId(new PartyroomId(fx.roomId))
                        .userId(new UserId(fx.botUid))
                        .linkId(LINK_LOW)
                        .name("Now Playing Low")
                        .build());
        PartyroomPlaybackData pp = partyroomPlaybackRepository.findById(new PartyroomId(fx.roomId)).orElseThrow();
        pp.activate(new PlaybackId(nowPlaying.getId()), new CrewId(1L));
        partyroomPlaybackRepository.saveAndFlush(pp);
        flushAndClear();

        when(songRecommendationProvider.recommend(any(), anyList(), anyInt()))
                .thenReturn(List.of("new song A"));
        when(pytubeSearchService.searchByWord(any(), anyInt()))
                .thenReturn(pytubeHit(LINK_NEW, "New Song A", "3:30"));

        selfUpdateService.tryUpdateRoom(new PartyroomId(fx.roomId));
        flushAndClear();

        // INV-3: 현재 재생 중인 LINK_LOW 는 최저점수임에도 여전히 playlist 에 남아 있어야 한다.
        assertThat(playlistLinkIds(fx.botPlaylistId)).contains(LINK_LOW);
    }

    // ───────────────────────────── fixtures ─────────────────────────────

    private record Fixture(long roomId, long botUid, long botPlaylistId, long fallbackPackId) {}

    /**
     * 활성 봇 DJ 1명이 있는 MANAGED 룸을 만든다(오케스트레이터 경로 재사용).
     * <ul>
     *   <li>송팩 A(vidLow/vidHigh) 적용 후 reconcile → 봇 playlist = {vidLow, vidHigh}</li>
     *   <li>self-update 폴백용 송팩 B(vidLow/vidHigh/vidPackFallback) 로 config 갱신
     *       — vidPackFallback 은 playlist 밖 미시도 트랙</li>
     * </ul>
     * watermark(lastSelfUpdateAt)는 reconcile 이 건드리지 않으므로 첫 호출 때 null(쿨다운 통과).
     */
    private Fixture seedActiveBotDjRoom() {
        long roomId = seedRoom(10);
        // 송팩 A — order 1=vidHigh(재생 시작 → 커서), 2~5=filler(점수 없음=0),
        // 6=vidLow(최저점수). vidLow 를 tail 에 두어 커서/다음곡 임박-재생 보호(INV-6)에서 벗어나게 한다.
        long packA = seedSongPack("Pack A",
                List.of(track(LINK_HIGH, "High Song", "3:00"),
                        track("vidF1", "Filler 1", "3:00"),
                        track("vidF2", "Filler 2", "3:00"),
                        track("vidF3", "Filler 3", "3:00"),
                        track("vidF4", "Filler 4", "3:00"),
                        track(LINK_LOW, "Low Song", "3:00")));

        seedManagedConfig(roomId, /*target*/1, /*floor*/0, packA);
        poolService.provision(1);
        flushAndClear();

        orchestrator.reconcileRoom(new PartyroomId(roomId));
        flushAndClear();

        // 활성 봇 DJ userId + 그 playlist id 조회.
        long botUid = activeBotDjUserId(roomId);
        long botPlaylistId = poolService.playlistIdOf(new UserId(botUid));

        // 폴백용 송팩 B — vidPackFallback 만 playlist 밖 미시도 트랙(나머지는 이미 playlist 에 있음).
        long packB = seedSongPack("Pack B",
                List.of(track(LINK_HIGH, "High Song", "3:00"),
                        track("vidF1", "Filler 1", "3:00"),
                        track("vidF2", "Filler 2", "3:00"),
                        track("vidF3", "Filler 3", "3:00"),
                        track("vidF4", "Filler 4", "3:00"),
                        track(LINK_LOW, "Low Song", "3:00"),
                        track(LINK_PACK_FALLBACK, "Fallback Song", "2:30")));

        // config 를 packB 로 갱신(MANAGED 유지). reconcile 이후 봇 1명 = target 충족이라
        // self-update 내부 게이트엔 영향 없음.
        PartyroomVirtualDjConfigData cfg = reloadConfig(roomId);
        cfg.applyManaged(1, 0, packB);
        configRepository.saveAndFlush(cfg);

        return new Fixture(roomId, botUid, botPlaylistId, packB);
    }

    /**
     * 봇의 plays 를 시드한다 — LINK_LOW(dislike 다수 → 최저점수), LINK_HIGH(like 다수).
     * 반응 history 는 LINK_LOW play 에 {@code reactions} 개 달아 비용 게이트(K)를 제어한다.
     */
    private void seedBotPlays(Fixture fx, int lowDislike, int highLike, int reactions) {
        PartyroomId pid = new PartyroomId(fx.roomId);
        UserId bot = new UserId(fx.botUid);

        PlaybackData lowPlay = playbackRepository.saveAndFlush(
                PlaybackData.builder().partyroomId(pid).userId(bot)
                        .linkId(LINK_LOW).name("Low Song").build());
        PlaybackData highPlay = playbackRepository.saveAndFlush(
                PlaybackData.builder().partyroomId(pid).userId(bot)
                        .linkId(LINK_HIGH).name("High Song").build());

        playbackAggregationRepository.saveAndFlush(PlaybackAggregationData.createFor(new PlaybackId(lowPlay.getId())));
        playbackAggregationRepository.applyAggregationDelta(new PlaybackId(lowPlay.getId()), 0, lowDislike, 0);

        playbackAggregationRepository.saveAndFlush(PlaybackAggregationData.createFor(new PlaybackId(highPlay.getId())));
        playbackAggregationRepository.applyAggregationDelta(new PlaybackId(highPlay.getId()), highLike, 0, 0);

        for (int i = 0; i < reactions; i++) {
            playbackReactionHistoryRepository.saveAndFlush(
                    new PlaybackReactionHistoryData(new UserId(2000L + i), new PlaybackId(lowPlay.getId())));
        }
    }

    private long seedRoom(int playbackTimeLimitMinutes) {
        PartyroomData p = PartyroomData.create(
                "vdj-selfupdate", "intro", LinkDomain.of("link-vdj-su-" + System.nanoTime()),
                PlaybackTimeLimit.ofMinutes(playbackTimeLimitMinutes), StageType.GENERAL,
                new UserId(8000L));
        long id = partyroomRepository.saveAndFlush(p).getId();
        PartyroomId pid = new PartyroomId(id);
        entityManager.persist(PartyroomPlaybackData.createFor(pid));
        entityManager.persist(DjQueueData.createFor(pid));
        entityManager.flush();
        return id;
    }

    private void seedManagedConfig(long roomId, int target, int floor, Long songPackId) {
        configRepository.save(PartyroomVirtualDjConfigData.builder()
                .partyroomId(roomId)
                .status(com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus.MANAGED)
                .targetCount(target).companionFloor(floor).songPackId(songPackId).build());
    }

    private record SeedTrack(String linkId, String name, String duration) {}

    private SeedTrack track(String linkId, String name, String duration) {
        return new SeedTrack(linkId, name, duration);
    }

    private long seedSongPack(String name, List<SeedTrack> tracks) {
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create(name, "IT"));
        int order = 1;
        for (SeedTrack t : tracks) {
            packTrackRepository.save(VirtualSongPackTrackData.create(
                    pack.getId(), order++, t.linkId(), t.name(), t.duration(), null));
        }
        return pack.getId();
    }

    private SearchResultDto pytubeHit(String videoId, String title, String runningTime) {
        return new SearchResultDto("ok",
                List.of(new SearchResultRawDto(videoId, title, "watch", runningTime, "thumb")));
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private PartyroomVirtualDjConfigData reloadConfig(long roomId) {
        return configRepository.findByPartyroomId(roomId).orElseThrow();
    }

    private List<String> playlistLinkIds(long playlistId) {
        return trackRepository.findAllByPlaylistId(new PlaylistId(playlistId)).stream()
                .map(TrackData::getLinkId)
                .collect(Collectors.toList());
    }

    /** 룸의 활성 봇(is_dummy=1) DJ 의 user_id 를 반환한다. */
    private long activeBotDjUserId(long roomId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "SELECT c.user_id FROM dj d " +
                        "JOIN crew c ON c.crew_id = d.crew_id " +
                        "JOIN user_account u ON u.user_id = c.user_id " +
                        "WHERE d.partyroom_id = :rid AND c.is_active = 1 AND u.is_dummy = 1 " +
                        "LIMIT 1")
                .setParameter("rid", roomId)
                .getSingleResult();
        return n.longValue();
    }
}
