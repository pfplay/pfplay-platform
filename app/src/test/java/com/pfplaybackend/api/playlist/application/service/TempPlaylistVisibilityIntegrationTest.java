package com.pfplaybackend.api.playlist.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.playlist.adapter.out.persistence.PlaylistRepository;
import com.pfplaybackend.api.playlist.application.dto.PlaylistSummaryDto;
import com.pfplaybackend.api.playlist.domain.entity.data.PlaylistData;
import com.pfplaybackend.api.playlist.domain.enums.PlaylistType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quick-DJ(#331) — TEMP 플리는 사용자 목록/단건 조회에서 숨겨진다(spec §3-1a).
 * GRABLIST/PLAYLIST 노출은 현행 유지(회귀 잠금).
 */
@Transactional
class TempPlaylistVisibilityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PlaylistRepository playlistRepository;

    @Test
    @DisplayName("findAllByUserId — TEMP 제외, GRABLIST/PLAYLIST 는 노출")
    void findAllExcludesTemp() {
        UserId owner = new UserId(9101L);
        entityManager.persist(PlaylistData.create(0, "그랩한 곡", PlaylistType.GRABLIST, owner));
        entityManager.persist(PlaylistData.create(1, "내 플레이리스트", PlaylistType.PLAYLIST, owner));
        entityManager.persist(PlaylistData.create(0, "Quick-DJ", PlaylistType.TEMP, owner));
        flushAndClear();

        List<PlaylistSummaryDto> result = playlistRepository.findAllByUserId(owner);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PlaylistSummaryDto::type)
                .containsExactlyInAnyOrder(PlaylistType.GRABLIST, PlaylistType.PLAYLIST);
    }

    @Test
    @DisplayName("findByIdAndUserId — TEMP id 단건 조회도 새지 않는다(null)")
    void findByIdExcludesTemp() {
        UserId owner = new UserId(9102L);
        PlaylistData temp = PlaylistData.create(0, "Quick-DJ", PlaylistType.TEMP, owner);
        entityManager.persist(temp);
        PlaylistData normal = PlaylistData.create(1, "내 플레이리스트", PlaylistType.PLAYLIST, owner);
        entityManager.persist(normal);
        flushAndClear();

        assertThat(playlistRepository.findByIdAndUserId(temp.getId(), owner)).isNull();
        assertThat(playlistRepository.findByIdAndUserId(normal.getId(), owner)).isNotNull();
    }
}
