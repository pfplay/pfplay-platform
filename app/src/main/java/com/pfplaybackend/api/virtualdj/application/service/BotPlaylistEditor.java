package com.pfplaybackend.api.virtualdj.application.service;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.playlist.adapter.out.persistence.TrackRepository;
import com.pfplaybackend.api.playlist.domain.entity.data.TrackData;
import com.pfplaybackend.api.virtualdj.application.dto.NewTrack;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 봇 playlist 의 트랙을 원자적으로 교체(prune + add-to-head + 순서 재정규화)하는 서비스.
 *
 * <p>P3-B 자가갱신 파이프라인에서 호출한다. {@code n = min(addTracks.size, pruneTrackIds.size)}
 * 개 트랙을 삭제하고 동일 개수의 신규 트랙을 헤드(1..n)에 추가한다. 기존 생존 트랙은
 * {@code n+1..n+m} 으로 순서가 재정규화되어 총 트랙 수(크기 불변)가 유지된다.
 *
 * <p>ArchUnit: {@code *Orchestrator*} 클래스가 아니므로 persistence 직접 접근 허용.
 */
@Service
@RequiredArgsConstructor
public class BotPlaylistEditor {

    private final TrackRepository trackRepository;

    /**
     * 봇 playlist 의 트랙을 원자적으로 교체한다.
     *
     * @param playlistId     봇 playlist id
     * @param pruneTrackIds  제거 후보 트랙 id 목록 (앞에서 n 개만 실제 삭제)
     * @param addTracks      추가할 신규 트랙 목록 (앞에서 n 개만 실제 추가)
     * @return 실제 교체된 트랙 수 n (0이면 변경 없음)
     */
    @Transactional
    public int swap(Long playlistId, List<Long> pruneTrackIds, List<NewTrack> addTracks) {
        int n = Math.min(addTracks.size(), pruneTrackIds.size());
        if (n == 0) {
            return 0;
        }

        // 1. 앞 n 개 트랙 삭제
        trackRepository.deleteAllById(pruneTrackIds.subList(0, n));

        // 2. 남은 트랙 로드 → 현재 orderNumber 오름차순 정렬
        List<TrackData> survivors = trackRepository.findAllByPlaylistId(new PlaylistId(playlistId))
                .stream()
                .sorted(Comparator.comparingInt(TrackData::getOrderNumber))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        // 3. 생존 트랙 순서를 n+1..n+m 으로 재정규화 (add-to-head: 신곡이 1..n 점유)
        int survivorOrder = n + 1;
        for (TrackData survivor : survivors) {
            survivor.reorder(survivorOrder++);
        }
        trackRepository.saveAll(survivors);

        // 4. 신규 트랙 n 개를 헤드(1..n)에 추가
        PlaylistId pid = new PlaylistId(playlistId);
        List<TrackData> newTracks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            NewTrack nt = addTracks.get(i);
            newTracks.add(TrackData.builder()
                    .playlistId(pid)
                    .orderNumber(i + 1)
                    .name(nt.name())
                    .linkId(nt.linkId())
                    .duration(nt.duration())
                    .thumbnailImage(nt.thumbnailImage())
                    .build());
        }
        trackRepository.saveAll(newTracks);

        return n;
    }
}
