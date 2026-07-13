package com.pfplaybackend.api.virtualcrew.application.service;

import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualcrew.application.dto.NewTrack;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualSongPackTrackData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 송 팩에서 아직 시도되지 않은(미시도) 곡 목록을 조회하는 폴백 소스.
 *
 * <p>excludeLinkIds 에 포함된 곡과 playbackTimeLimit 을 초과하는 곡을 제외하고,
 * order_number 순서대로 최대 limit 개 반환한다.
 *
 * <p>SongPackApplier 는 건드리지 않는다 — 동일 레포를 재사용만 한다.
 */
@Service
@RequiredArgsConstructor
public class SongPackReservoir {

    private final VirtualSongPackTrackRepository songPackTrackRepository;

    /**
     * 미시도 곡 목록을 order_number 순으로 반환한다.
     *
     * @param songPackId        대상 송 팩 id
     * @param excludeLinkIds    이미 playlist 에 있거나 최근에 사용된 linkId 세트
     * @param timeLimitMinutes  재생 시간 제한(분). 0 이하 = unlimited
     * @param limit             최대 반환 수
     * @return in-limit 미시도 곡 리스트 (빈 리스트 가능)
     */
    public List<NewTrack> untried(Long songPackId, Set<String> excludeLinkIds,
                                  int timeLimitMinutes, int limit) {
        List<VirtualSongPackTrackData> packTracks =
                songPackTrackRepository.findBySongPackIdOrderByOrderNumberAsc(songPackId);

        PlaybackTimeLimit timeLimit = PlaybackTimeLimit.ofMinutes(timeLimitMinutes);

        return packTracks.stream()
                .filter(t -> !excludeLinkIds.contains(t.getLinkId()))
                .filter(t -> {
                    Duration dur = Duration.fromString(t.getDuration());
                    return !timeLimit.exceedsDuration(dur);
                })
                .limit(limit)
                .map(t -> new NewTrack(
                        t.getName(),
                        t.getLinkId(),
                        Duration.fromString(t.getDuration()),
                        t.getThumbnailImage()))
                .toList();
    }
}
