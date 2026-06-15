package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.application.service.PartyroomQueryService;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepository;
import com.pfplaybackend.api.playlist.application.dto.search.SearchResultDto;
import com.pfplaybackend.api.playlist.application.dto.search.SearchResultRawDto;
import com.pfplaybackend.api.playlist.application.service.search.PytubeSearchService;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 가상 DJ P3-B 플레이리스트 자가갱신 사이클 조립기(assembler).
 *
 * <p>룸 레벨 게이트(MANAGED · 쿨다운 · 신규 반응 ≥ K)를 통과하면 룸의 각 봇마다 한 사이클씩
 * 실행한다: 점수 정렬 → 보호곡 산정 → LLM 리필 → Pytube 해소 → 송팩 폴백 → 원자적 swap →
 * prune 기록. 모든 봇 처리 후 룸 watermark 를 한 번 전진시킨다.
 *
 * <p><b>INV-2 (비용 게이트):</b> 신규 반응 수 < K 이면 어떤 LLM 호출도 하기 전에 즉시 반환한다.
 * 조용한/빈 룸은 LLM 비용 0 으로 빠져나간다.
 *
 * <p>ArchUnit: 클래스명에 {@code Orchestrator} 를 포함하지 않으므로 application service /
 * playlist·track repo 직접 접근이 허용된다(가상 DJ 가드 규칙 A 비대상).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlaylistSelfUpdateService {

    private final PartyroomVirtualDjConfigRepository configRepository;
    private final SelfUpdateConfig selfUpdateConfig;
    private final ActiveDjSnapshotService activeDjSnapshotService;
    private final ReactionScoreReader reactionScoreReader;
    private final RoomContextReader roomContextReader;
    private final PartyroomQueryService partyroomQueryService;
    private final VirtualUserPoolService virtualUserPoolService;
    private final TrackRepository trackRepository;
    private final PlaylistRepository playlistRepository;
    private final SongRecommendationProvider songRecommendationProvider;
    private final PytubeSearchService pytubeSearchService;
    private final SongPackReservoir songPackReservoir;
    private final RecentlyPrunedStore recentlyPrunedStore;
    private final BotPlaylistEditor botPlaylistEditor;
    private final Clock clock;

    /**
     * 한 룸의 자가갱신 사이클을 시도한다(게이트 통과 시에만 실제 갱신).
     */
    public void tryUpdateRoom(PartyroomId roomId) {
        LocalDateTime now = LocalDateTime.now(clock);

        PartyroomVirtualDjConfigData config = configRepository.findByPartyroomId(roomId.getId()).orElse(null);
        if (config == null || config.getStatus() != VirtualDjStatus.MANAGED) {
            return;
        }

        // 쿨다운 — watermark null(첫 사이클)은 통과.
        LocalDateTime watermark = config.getLastSelfUpdateAt();
        if (watermark != null
                && java.time.Duration.between(watermark, now).getSeconds() < selfUpdateConfig.cooldownSeconds()) {
            return;
        }

        ActiveDjSnapshot snapshot = activeDjSnapshotService.snapshot(roomId);
        List<UserId> bots = snapshot.botUserIdsByJoinedDesc();
        if (bots.isEmpty()) {
            return;
        }

        // ⭐ INV-2: 비용 게이트 — 어떤 LLM 호출보다 먼저 신규 반응 수를 확인한다.
        List<Long> botIdLongs = bots.stream().map(UserId::getUid).toList();
        if (reactionScoreReader.countNewReactions(botIdLongs, roomId.getId(), watermark)
                < selfUpdateConfig.minReactions()) {
            return;
        }

        RoomContext roomCtx = roomContextReader.read(roomId);
        String nowPlayingLinkId = partyroomQueryService.getCurrentPlaybackLinkId(roomId); // nullable
        int limitMin = partyroomQueryService.getPartyroomById(roomId).getPlaybackTimeLimit().getMinutes();

        for (UserId botUserId : bots) {
            try {
                updateBot(botUserId, roomId, roomCtx, nowPlayingLinkId, limitMin, config.getSongPackId());
            } catch (Exception e) {
                log.warn("[vdj-selfupdate] bot {} failed: {}", botUserId.getUid(), e.getMessage());
            }
        }

        config.markSelfUpdated(now);
        configRepository.save(config);
    }

    private void updateBot(UserId botUserId, PartyroomId roomId, RoomContext roomCtx,
                           String nowPlayingLinkId, int limitMin, Long songPackId) {
        int replacePerCycle = selfUpdateConfig.replacePerCycle();

        List<ScoredLink> scoredAsc = reactionScoreReader.scoredAscending(botUserId, roomId); // 오름차순(낮은 점수 먼저)
        Long playlistId = virtualUserPoolService.playlistIdOf(botUserId);
        List<TrackData> tracks = trackRepository.findAllByPlaylistId(new PlaylistId(playlistId));

        Set<String> trackLinkIds = tracks.stream().map(TrackData::getLinkId).collect(Collectors.toSet());
        Map<String, Long> trackIdByLink = tracks.stream()
                .collect(Collectors.toMap(TrackData::getLinkId, TrackData::getId, (a, b) -> a));
        Map<String, String> nameByLink = tracks.stream()
                .collect(Collectors.toMap(TrackData::getLinkId, TrackData::getName, (a, b) -> a));

        // 보호곡 — 현재곡(INV-3) + 커서/다음곡(임박 재생 보호) + 최근 prune(INV-6 진동 방지).
        Set<String> protectedLinks = new HashSet<>();
        if (nowPlayingLinkId != null) {
            protectedLinks.add(nowPlayingLinkId);
        }
        protectedLinks.addAll(cursorAndNextLinkIds(playlistId, tracks));
        protectedLinks.addAll(recentlyPrunedStore.recentlyPruned(botUserId));

        // prune 후보 — playlist 안에 있고 보호되지 않은, 점수 낮은 곡 최대 P 개.
        List<ScoredLink> pruneCandidates = scoredAsc.stream()
                .filter(s -> trackLinkIds.contains(s.linkId()) && !protectedLinks.contains(s.linkId()))
                .limit(replacePerCycle)
                .toList();
        if (pruneCandidates.isEmpty()) {
            return; // 교체할 가치가 있는 곡 없음
        }
        List<Long> pruneTrackIds = pruneCandidates.stream()
                .map(s -> trackIdByLink.get(s.linkId()))
                .toList();
        List<String> prunedLinkOrder = pruneCandidates.stream()
                .map(ScoredLink::linkId)
                .toList();

        // 우승 곡명(LLM 취향 단서) — 점수 높은 순.
        List<String> winnerTitles = scoredAsc.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(replacePerCycle)
                .map(s -> nameByLink.get(s.linkId()))
                .filter(Objects::nonNull)
                .toList();

        // LLM 리필 (best-effort).
        List<String> recommended = songRecommendationProvider.recommend(
                roomCtx, winnerTitles, selfUpdateConfig.recommendCount());

        List<NewTrack> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>(trackLinkIds);
        seen.addAll(protectedLinks);
        PlaybackTimeLimit timeLimit = PlaybackTimeLimit.ofMinutes(limitMin);

        for (String query : recommended) {
            if (resolved.size() >= pruneTrackIds.size()) {
                break;
            }
            SearchResultDto searchResult = pytubeSearchService.searchByWord(query, 1);
            if (searchResult == null || searchResult.data() == null || searchResult.data().isEmpty()) {
                continue;
            }
            SearchResultRawDto raw = searchResult.data().get(0);
            Duration duration = Duration.fromString(raw.running_time());
            if (timeLimit.exceedsDuration(duration)) {
                continue;
            }
            if (!seen.add(raw.video_id())) {
                continue; // 이미 playlist/보호/직전 해소에 있음
            }
            resolved.add(new NewTrack(raw.video_title(), raw.video_id(), duration, raw.thumbnail_url()));
        }

        // 부족하면 송팩 폴백.
        if (resolved.size() < pruneTrackIds.size()) {
            int need = pruneTrackIds.size() - resolved.size();
            List<NewTrack> fill = songPackReservoir.untried(songPackId, seen, limitMin, need);
            for (NewTrack track : fill) {
                if (seen.add(track.linkId())) {
                    resolved.add(track);
                }
            }
        }

        int swapped = botPlaylistEditor.swap(playlistId, pruneTrackIds, resolved); // editor 가 n=min 적용
        if (swapped > 0) {
            recentlyPrunedStore.markPruned(botUserId, prunedLinkOrder.subList(0, swapped));
        }
    }

    /**
     * 커서(마지막 재생 곡)와 그 다음(order_number 더 큰 첫 곡)의 linkId 를 임박 재생 보호용으로 반환한다.
     * 커서가 없거나 트랙을 못 찾으면 빈 set. 절대 throw 하지 않는다(방어적).
     */
    private Set<String> cursorAndNextLinkIds(Long playlistId, List<TrackData> tracks) {
        Set<String> result = new HashSet<>();
        try {
            Optional<PlaylistData> playlist = playlistRepository.findById(playlistId);
            if (playlist.isEmpty() || playlist.get().getLastPlayedTrackId() == null) {
                return result;
            }
            Long cursorId = playlist.get().getLastPlayedTrackId();
            TrackData cursorTrack = tracks.stream()
                    .filter(t -> Objects.equals(t.getId(), cursorId))
                    .findFirst()
                    .orElse(null);
            if (cursorTrack == null) {
                return result;
            }
            result.add(cursorTrack.getLinkId());

            int cursorOrder = cursorTrack.getOrderNumber();
            tracks.stream()
                    .filter(t -> t.getOrderNumber() != null && t.getOrderNumber() > cursorOrder)
                    .min((a, b) -> Integer.compare(a.getOrderNumber(), b.getOrderNumber()))
                    .ifPresent(next -> result.add(next.getLinkId()));
        } catch (Exception e) {
            log.warn("[vdj-selfupdate] cursor protection skipped for playlist {}: {}", playlistId, e.getMessage());
        }
        return result;
    }
}
