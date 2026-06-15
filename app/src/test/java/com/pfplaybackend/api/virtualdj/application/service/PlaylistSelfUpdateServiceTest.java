package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepository;
import com.pfplaybackend.api.playlist.application.dto.search.SearchResultDto;
import com.pfplaybackend.api.playlist.application.dto.search.SearchResultRawDto;
import com.pfplaybackend.api.playlist.application.service.search.PytubeSearchService;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.PartyroomVirtualDjConfigRepository;
import com.pfplaybackend.api.virtualdj.application.dto.NewTrack;
import com.pfplaybackend.api.virtualdj.application.port.RoomContextReader;
import com.pfplaybackend.api.virtualdj.application.port.RoomContextReader.RoomContext;
import com.pfplaybackend.api.virtualdj.application.port.SongRecommendationProvider;
import com.pfplaybackend.api.virtualdj.application.service.ActiveDjSnapshotService.ActiveDjSnapshot;
import com.pfplaybackend.api.virtualdj.application.service.ReactionScoreReader.ScoredLink;
import com.pfplaybackend.api.virtualdj.domain.entity.data.PartyroomVirtualDjConfigData;
import com.pfplaybackend.api.virtualdj.domain.enums.VirtualDjStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlaylistSelfUpdateServiceTest {

    private static final long ROOM = 99L;
    private static final long BOT = 7L;
    private static final long PLAYLIST_ID = 500L;
    private static final long SONG_PACK_ID = 42L;
    private static final int COOLDOWN = 1800;
    private static final int K = 5;     // minReactions
    private static final int P = 3;     // replacePerCycle
    private static final int N = 6;     // recommendCount
    private static final int LIMIT_MIN = 10;

    private final PartyroomVirtualDjConfigRepository configRepository = mock(PartyroomVirtualDjConfigRepository.class);
    private final SelfUpdateConfig selfUpdateConfig = mock(SelfUpdateConfig.class);
    private final ActiveDjSnapshotService activeDjSnapshotService = mock(ActiveDjSnapshotService.class);
    private final ReactionScoreReader reactionScoreReader = mock(ReactionScoreReader.class);
    private final RoomContextReader roomContextReader = mock(RoomContextReader.class);
    private final PartyroomQueryService partyroomQueryService = mock(PartyroomQueryService.class);
    private final VirtualUserPoolService virtualUserPoolService = mock(VirtualUserPoolService.class);
    private final TrackRepository trackRepository = mock(TrackRepository.class);
    private final PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
    private final SongRecommendationProvider songRecommendationProvider = mock(SongRecommendationProvider.class);
    private final PytubeSearchService pytubeSearchService = mock(PytubeSearchService.class);
    private final SongPackReservoir songPackReservoir = mock(SongPackReservoir.class);
    private final RecentlyPrunedStore recentlyPrunedStore = mock(RecentlyPrunedStore.class);
    private final BotPlaylistEditor botPlaylistEditor = mock(BotPlaylistEditor.class);

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-03T12:00:00Z"), ZoneId.of("UTC"));
    private final LocalDateTime now = LocalDateTime.now(clock);

    private final PartyroomId roomId = new PartyroomId(ROOM);
    private final UserId botUserId = new UserId(BOT);

    private PlaylistSelfUpdateService service;

    @BeforeEach
    void setUp() {
        service = new PlaylistSelfUpdateService(
                configRepository, selfUpdateConfig, activeDjSnapshotService, reactionScoreReader,
                roomContextReader, partyroomQueryService, virtualUserPoolService, trackRepository,
                playlistRepository, songRecommendationProvider, pytubeSearchService, songPackReservoir,
                recentlyPrunedStore, botPlaylistEditor, clock);

        when(selfUpdateConfig.cooldownSeconds()).thenReturn(COOLDOWN);
        when(selfUpdateConfig.minReactions()).thenReturn(K);
        when(selfUpdateConfig.replacePerCycle()).thenReturn(P);
        when(selfUpdateConfig.recommendCount()).thenReturn(N);
    }

    // ── fixtures ──

    /** MANAGED config with the given watermark (null = first cycle). */
    private PartyroomVirtualDjConfigData managedConfig(LocalDateTime watermark) {
        PartyroomVirtualDjConfigData config = PartyroomVirtualDjConfigData.create(ROOM);
        config.applyManaged(2, 1, SONG_PACK_ID);
        if (watermark != null) {
            config.markSelfUpdated(watermark);
        }
        return config;
    }

    private TrackData track(long id, String linkId, String name, int orderNumber) {
        TrackData t = TrackData.builder()
                .playlistId(new com.pfplaybackend.api.common.domain.value.PlaylistId(PLAYLIST_ID))
                .orderNumber(orderNumber)
                .name(name)
                .linkId(linkId)
                .duration(Duration.fromString("3:00"))
                .thumbnailImage("thumb")
                .build();
        // id is generated; set via reflection for the test
        try {
            java.lang.reflect.Field f = TrackData.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return t;
    }

    private SearchResultDto pytubeHit(String videoId, String title, String runningTime) {
        return new SearchResultDto("ok",
                List.of(new SearchResultRawDto(videoId, title, "watch", runningTime, "thumb")));
    }

    /** Wire the happy-path collaborators: one bot, low-score track lowLink in playlist + a higher-score track. */
    private void wireHappyPath(String lowLink, String highLink, String nowPlayingLinkId) {
        when(configRepository.findByPartyroomId(ROOM)).thenReturn(Optional.of(managedConfig(null)));
        when(activeDjSnapshotService.snapshot(roomId)).thenReturn(new ActiveDjSnapshot(1, List.of(botUserId)));
        when(reactionScoreReader.countNewReactions(eq(List.of(BOT)), eq(ROOM), any())).thenReturn((long) K);
        when(roomContextReader.read(roomId)).thenReturn(new RoomContext("title", "intro", "np"));
        when(partyroomQueryService.getCurrentPlaybackLinkId(roomId)).thenReturn(nowPlayingLinkId);

        PartyroomData partyroom = mock(PartyroomData.class, RETURNS_DEEP_STUBS);
        when(partyroom.getPlaybackTimeLimit()).thenReturn(PlaybackTimeLimit.ofMinutes(LIMIT_MIN));
        when(partyroomQueryService.getPartyroomById(roomId)).thenReturn(partyroom);

        when(virtualUserPoolService.playlistIdOf(botUserId)).thenReturn(PLAYLIST_ID);
        List<TrackData> tracks = List.of(
                track(101L, lowLink, "Low Song", 1),
                track(102L, highLink, "High Song", 2));
        when(trackRepository.findAllByPlaylistId(any())).thenReturn(tracks);
        // ascending: low score first
        when(reactionScoreReader.scoredAscending(botUserId, roomId)).thenReturn(List.of(
                new ScoredLink(lowLink, -3.0),
                new ScoredLink(highLink, 5.0)));
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.empty());
        when(recentlyPrunedStore.recentlyPruned(botUserId)).thenReturn(Set.of());
    }

    // ── cases ──

    @Test
    @DisplayName("1. count < K → no-op (INV-2): LLM·swap 미호출 + watermark 미전진")
    void countBelowK_noOp() {
        when(configRepository.findByPartyroomId(ROOM)).thenReturn(Optional.of(managedConfig(null)));
        when(activeDjSnapshotService.snapshot(roomId)).thenReturn(new ActiveDjSnapshot(1, List.of(botUserId)));
        when(reactionScoreReader.countNewReactions(eq(List.of(BOT)), eq(ROOM), any())).thenReturn((long) (K - 1));

        service.tryUpdateRoom(roomId);

        verifyNoInteractions(songRecommendationProvider);
        verifyNoInteractions(botPlaylistEditor);
        verify(configRepository, never()).save(any());
    }

    @Test
    @DisplayName("2. 쿨다운 미경과 → 즉시 no-op (snapshot/count/LLM/swap 미호출)")
    void cooldownNotElapsed_noOp() {
        when(configRepository.findByPartyroomId(ROOM)).thenReturn(Optional.of(managedConfig(now.minusSeconds(10))));

        service.tryUpdateRoom(roomId);

        verifyNoInteractions(activeDjSnapshotService);
        verifyNoInteractions(reactionScoreReader);
        verifyNoInteractions(songRecommendationProvider);
        verifyNoInteractions(botPlaylistEditor);
        verify(configRepository, never()).save(any());
    }

    @Test
    @DisplayName("3. config 부재 → no-op")
    void configAbsent_noOp() {
        when(configRepository.findByPartyroomId(ROOM)).thenReturn(Optional.empty());

        service.tryUpdateRoom(roomId);

        verifyNoInteractions(activeDjSnapshotService);
        verifyNoInteractions(botPlaylistEditor);
        verify(configRepository, never()).save(any());
    }

    @Test
    @DisplayName("3b. config MANAGED 아님 → no-op")
    void configNotManaged_noOp() {
        PartyroomVirtualDjConfigData off = PartyroomVirtualDjConfigData.create(ROOM); // status=OFF
        when(configRepository.findByPartyroomId(ROOM)).thenReturn(Optional.of(off));

        service.tryUpdateRoom(roomId);

        verifyNoInteractions(activeDjSnapshotService);
        verifyNoInteractions(botPlaylistEditor);
        verify(configRepository, never()).save(any());
    }

    @Test
    @DisplayName("4. count≥K & 쿨다운 경과 & happy path → swap 호출 + watermark 전진")
    void happyPath_swapAndWatermark() {
        wireHappyPath("low", "high", null);
        when(songRecommendationProvider.recommend(any(), anyList(), eq(N)))
                .thenReturn(List.of("new song A"));
        when(pytubeSearchService.searchByWord(eq("new song A"), eq(1)))
                .thenReturn(pytubeHit("vidA", "New Song A", "3:30"));
        when(botPlaylistEditor.swap(eq(PLAYLIST_ID), anyList(), anyList())).thenReturn(1);

        service.tryUpdateRoom(roomId);

        verify(botPlaylistEditor).swap(eq(PLAYLIST_ID), anyList(), anyList());
        ArgumentCaptor<PartyroomVirtualDjConfigData> captor = ArgumentCaptor.forClass(PartyroomVirtualDjConfigData.class);
        verify(configRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getLastSelfUpdateAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("5. LLM 빈 결과 → 송팩 폴백으로 swap")
    void llmEmpty_songPackFallback() {
        wireHappyPath("low", "high", null);
        when(songRecommendationProvider.recommend(any(), anyList(), eq(N))).thenReturn(List.of());
        List<NewTrack> fill = List.of(new NewTrack("Pack Song", "packVid", Duration.fromString("2:30"), "thumb"));
        when(songPackReservoir.untried(eq(SONG_PACK_ID), anySet(), eq(LIMIT_MIN), anyInt())).thenReturn(fill);

        ArgumentCaptor<List<NewTrack>> addCaptor = ArgumentCaptor.forClass(List.class);
        when(botPlaylistEditor.swap(eq(PLAYLIST_ID), anyList(), addCaptor.capture())).thenReturn(1);

        service.tryUpdateRoom(roomId);

        verify(pytubeSearchService, never()).searchByWord(any(), anyInt());
        verify(botPlaylistEditor).swap(eq(PLAYLIST_ID), anyList(), anyList());
        assertThat(addCaptor.getValue()).extracting(NewTrack::linkId).contains("packVid");
    }

    @Test
    @DisplayName("6. 현재곡 보호 (INV-3): nowPlayingLinkId == 최저점수 곡 → prune 목록에서 제외")
    void currentTrackProtected() {
        // lowLink is both lowest-score AND the now-playing track → must be protected
        wireHappyPath("low", "high", "low");
        when(songRecommendationProvider.recommend(any(), anyList(), eq(N)))
                .thenReturn(List.of("new song A"));
        when(pytubeSearchService.searchByWord(any(), eq(1)))
                .thenReturn(pytubeHit("vidA", "New Song A", "3:30"));

        ArgumentCaptor<List<Long>> pruneCaptor = ArgumentCaptor.forClass(List.class);
        when(botPlaylistEditor.swap(eq(PLAYLIST_ID), pruneCaptor.capture(), anyList())).thenReturn(1);

        service.tryUpdateRoom(roomId);

        // 101 = trackId of "low" (now-playing) → must NOT be in prune list.
        // "high"(102) is the only non-protected in-playlist track → it is the prune candidate.
        verify(botPlaylistEditor).swap(eq(PLAYLIST_ID), anyList(), anyList());
        assertThat(pruneCaptor.getValue()).doesNotContain(101L);
        assertThat(pruneCaptor.getValue()).containsExactly(102L);
    }
}
