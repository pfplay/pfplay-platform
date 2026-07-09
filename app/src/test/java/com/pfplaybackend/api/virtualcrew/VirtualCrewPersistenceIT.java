package com.pfplaybackend.api.virtualcrew;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualSongPackRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualSongPackTrackRepository;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualSongPackData;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualSongPackTrackData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class VirtualCrewPersistenceIT extends AbstractIntegrationTest {

    @Autowired private VirtualSongPackRepository virtualSongPackRepository;
    @Autowired private VirtualSongPackTrackRepository virtualSongPackTrackRepository;
    @Autowired private PartyroomVirtualCrewConfigRepository partyroomVirtualCrewConfigRepository;

    @Test
    @DisplayName("송팩과 트랙 저장·조회")
    void 송팩과_트랙_저장_조회() {
        VirtualSongPackData pack = virtualSongPackRepository.save(
                VirtualSongPackData.create("K-POP Hot", "케이팝"));

        virtualSongPackTrackRepository.save(
                VirtualSongPackTrackData.create(
                        pack.getId(), 1, "POe9SOEKotk", "Shut Down", "03:01",
                        "https://i.ytimg.com/x"));

        flushAndClear();

        List<VirtualSongPackTrackData> tracks =
                virtualSongPackTrackRepository.findBySongPackIdOrderByOrderNumberAsc(pack.getId());

        assertThat(tracks).hasSize(1);
        assertThat(tracks.get(0).getLinkId()).isEqualTo("POe9SOEKotk");
    }

    @Test
    @DisplayName("config 기본값과 전이")
    void config_기본값과_전이() {
        PartyroomVirtualCrewConfigData cfg = PartyroomVirtualCrewConfigData.create(1L);

        assertThat(cfg.getStatus()).isEqualTo(VirtualCrewStatus.OFF);

        cfg.applyManaged(2, 1, 10L);
        assertThat(cfg.getStatus()).isEqualTo(VirtualCrewStatus.MANAGED);

        partyroomVirtualCrewConfigRepository.save(cfg);
        flushAndClear();
        assertThat(partyroomVirtualCrewConfigRepository.findByPartyroomId(1L)).isPresent();

        cfg.turnOff();
        assertThat(cfg.getStatus()).isEqualTo(VirtualCrewStatus.OFF);
    }
}
