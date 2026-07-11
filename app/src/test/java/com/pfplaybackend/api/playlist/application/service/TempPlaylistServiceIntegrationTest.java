package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.application.dto.command.AddTrackCommand;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import com.pfplaybackend.api.playlist.domain.port.PlaylistAggregatePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quick-DJ(#331) — TEMP 플리 준비(find-or-create + 전곡 리셋 + 단건 삽입)(spec §3-2 step3~5).
 * per-user 1개 재사용으로 행 증식이 없음을 잠근다(spec 결정6).
 */
@Transactional
class TempPlaylistServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TempPlaylistService tempPlaylistService;
    @Autowired
    private PlaylistAggregatePort aggregatePort;

    private AddTrackCommand track(String linkId, String name) {
        return new AddTrackCommand(name, linkId, "3:45", "https://i.ytimg.com/vi/" + linkId + "/mqdefault.jpg");
    }

    @Test
    @DisplayName("TEMP 미존재 → 생성 + 곡 1개 삽입")
    void createsTempWhenAbsent() {
        UserId owner = new UserId(9201L);

        Long playlistId = tempPlaylistService.prepareOneShotPlaylist(owner, track("aaa111", "곡A"));
        flushAndClear();

        List<PlaylistData> temps = aggregatePort.findPlaylistsByOwnerAndType(owner, PlaylistType.TEMP);
        assertThat(temps).hasSize(1);
        assertThat(temps.get(0).getId()).isEqualTo(playlistId);
        assertThat(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(playlistId), "aaa111")).isPresent();
    }

    @Test
    @DisplayName("재호출 — 같은 TEMP 재사용(행 증식 없음), 이전 곡은 리셋되고 새 곡만 남는다")
    void reusesAndResetsTemp() {
        UserId owner = new UserId(9202L);

        Long first = tempPlaylistService.prepareOneShotPlaylist(owner, track("aaa111", "곡A"));
        flushAndClear();
        Long second = tempPlaylistService.prepareOneShotPlaylist(owner, track("bbb222", "곡B"));
        flushAndClear();

        assertThat(second).isEqualTo(first);
        assertThat(aggregatePort.findPlaylistsByOwnerAndType(owner, PlaylistType.TEMP)).hasSize(1);
        assertThat(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(first), "aaa111")).isEmpty();
        assertThat(aggregatePort.findTrackByPlaylistAndLink(new PlaylistId(first), "bbb222")).isPresent();
    }
}
