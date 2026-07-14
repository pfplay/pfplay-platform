package com.pfplaybackend.api.party.domain.entity.data;

import com.pfplaybackend.api.common.domain.value.PlaylistId;
import com.pfplaybackend.api.party.domain.enums.DjKind;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DjDataTest {

    @Test
    @DisplayName("create — 팩토리 메서드가 모든 필드를 설정한다")
    void createSetsAllFields() {
        // given
        PartyroomId partyroomId = new PartyroomId(1L);
        PlaylistId playlistId = new PlaylistId(10L);
        CrewId crewId = new CrewId(20L);

        // when
        DjData dj = DjData.create(partyroomId, playlistId, crewId, 3);

        // then
        assertThat(dj.getPartyroomId()).isEqualTo(partyroomId);
        assertThat(dj.getPlaylistId()).isEqualTo(playlistId);
        assertThat(dj.getCrewId()).isEqualTo(crewId);
        assertThat(dj.getOrderNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("updateOrderNumber — 순서가 업데이트된다")
    void updateOrderNumberUpdatesOrder() {
        // given
        DjData dj = DjData.create(new PartyroomId(1L), new PlaylistId(10L), new CrewId(20L), 1);

        // when
        dj.updateOrderNumber(5);

        // then
        assertThat(dj.getOrderNumber()).isEqualTo(5);
    }

    @Test
    @DisplayName("updatePlaylist — playlist 가 변경되고 orderNumber 는 보존된다")
    void updatePlaylistKeepsOrderNumber() {
        // given
        PlaylistId oldId = new PlaylistId(10L);
        PlaylistId newId = new PlaylistId(99L);
        DjData dj = DjData.create(new PartyroomId(1L), oldId, new CrewId(20L), 3);

        // when
        dj.updatePlaylist(newId);

        // then
        assertThat(dj.getPlaylistId()).isEqualTo(newId);
        assertThat(dj.getOrderNumber()).isEqualTo(3);
        assertThat(dj.getCrewId()).isEqualTo(new CrewId(20L));
        assertThat(dj.getPartyroomId()).isEqualTo(new PartyroomId(1L));
    }

    // ── Quick-DJ(#331) — DjKind ──

    @Test
    @DisplayName("create(4-arg) — kind 미지정 생성은 NORMAL")
    void createDefaultsToNormal() {
        DjData dj = DjData.create(new PartyroomId(1L), new PlaylistId(2L), new CrewId(3L), 1);
        assertThat(dj.getKind()).isEqualTo(DjKind.NORMAL);
    }

    @Test
    @DisplayName("create(5-arg) — ONE_SHOT 지정 생성")
    void createWithOneShotKind() {
        DjData dj = DjData.create(new PartyroomId(1L), new PlaylistId(2L), new CrewId(3L), 1, DjKind.ONE_SHOT);
        assertThat(dj.getKind()).isEqualTo(DjKind.ONE_SHOT);
    }
}
