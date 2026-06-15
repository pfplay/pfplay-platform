package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.Duration;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualdj.application.dto.NewTrack;
import com.pfplaybackend.api.virtualdj.application.service.SongPackReservoir;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualdj.domain.entity.data.VirtualSongPackTrackData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class SongPackReservoirIT extends AbstractIntegrationTest {

    @Autowired private SongPackReservoir reservoir;
    @Autowired private VirtualSongPackRepository packRepository;
    @Autowired private VirtualSongPackTrackRepository trackRepository;

    @Test
    @DisplayName("excludeLinkIds + timeLimitMinutes 필터 후 order_number 순 반환")
    void untried_필터_및_순서_검증() {
        // 송 팩 생성
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("테스트 팩", "IT 검증용"));
        Long packId = pack.getId();

        // 5개 트랙: order 1=2분/id1, 2=3분/id2, 3=2분/id3, 4=4분/id4, 5=6분/id5(초과)
        trackRepository.save(VirtualSongPackTrackData.create(packId, 1, "id1", "Song 1", "2:00", null));
        trackRepository.save(VirtualSongPackTrackData.create(packId, 2, "id2", "Song 2", "3:00", null));
        trackRepository.save(VirtualSongPackTrackData.create(packId, 3, "id3", "Song 3", "2:00", null));
        trackRepository.save(VirtualSongPackTrackData.create(packId, 4, "id4", "Song 4", "4:00", null));
        trackRepository.save(VirtualSongPackTrackData.create(packId, 5, "id5", "Song 5", "6:00", null));

        entityManager.flush();
        entityManager.clear();

        // 제외: id2(order2), id4(order4) / 시간 제한: 5분 → id5(6분) 초과
        // 남는 것: id1(order1,2분), id3(order3,2분)
        List<NewTrack> result = reservoir.untried(packId, Set.of("id2", "id4"), 5, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).linkId()).isEqualTo("id1");
        assertThat(result.get(0).name()).isEqualTo("Song 1");
        assertThat(result.get(0).duration()).isEqualTo(Duration.fromString("2:00"));
        assertThat(result.get(1).linkId()).isEqualTo("id3");
        assertThat(result.get(1).name()).isEqualTo("Song 3");
    }

    @Test
    @DisplayName("limit 로 반환 수 제한")
    void untried_limit_적용() {
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("리밋 팩", "limit 테스트"));
        Long packId = pack.getId();

        trackRepository.save(VirtualSongPackTrackData.create(packId, 1, "lim1", "L1", "1:00", null));
        trackRepository.save(VirtualSongPackTrackData.create(packId, 2, "lim2", "L2", "1:00", null));
        trackRepository.save(VirtualSongPackTrackData.create(packId, 3, "lim3", "L3", "1:00", null));

        entityManager.flush();
        entityManager.clear();

        List<NewTrack> result = reservoir.untried(packId, Set.of(), 0, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).linkId()).isEqualTo("lim1");
        assertThat(result.get(1).linkId()).isEqualTo("lim2");
    }

    @Test
    @DisplayName("unlimited(timeLimitMinutes=0) 이면 긴 곡도 포함")
    void untried_unlimited_긴곡_포함() {
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("무제한 팩", "unlimited 테스트"));
        Long packId = pack.getId();

        trackRepository.save(VirtualSongPackTrackData.create(packId, 1, "long1", "Long Song", "60:00", null));

        entityManager.flush();
        entityManager.clear();

        List<NewTrack> result = reservoir.untried(packId, Set.of(), 0, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).linkId()).isEqualTo("long1");
    }

    @Test
    @DisplayName("모두 제외되면 빈 리스트 반환")
    void untried_모두제외_빈리스트() {
        VirtualSongPackData pack = packRepository.save(VirtualSongPackData.create("빈 팩", "empty 테스트"));
        Long packId = pack.getId();

        trackRepository.save(VirtualSongPackTrackData.create(packId, 1, "ex1", "Excluded", "2:00", null));

        entityManager.flush();
        entityManager.clear();

        List<NewTrack> result = reservoir.untried(packId, Set.of("ex1"), 5, 10);

        assertThat(result).isEmpty();
    }
}
