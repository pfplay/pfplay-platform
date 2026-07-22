package com.pfplaybackend.api.party.adapter.out.persistence;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.DisplayFlag;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class PartyroomRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PartyroomRepository partyroomRepository;

    @Test
    @DisplayName("PartyroomData 저장 후 조회 시 JPA 매핑이 올바르게 동작한다")
    void saveAndFindById() {
        // given
        UserId hostId = new UserId(100L);
        PartyroomData partyroom = PartyroomData.create(
                "테스트 파티룸", "통합 테스트용 파티룸입니다",
                LinkDomain.of("test-link"), PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL, hostId);

        // when
        PartyroomData saved = partyroomRepository.save(partyroom);
        flushAndClear();
        Optional<PartyroomData> found = partyroomRepository.findById(saved.getId());

        // then
        assertThat(found).isPresent();
        PartyroomData loaded = found.get();
        assertThat(loaded.getTitle()).isEqualTo("테스트 파티룸");
        assertThat(loaded.getIntroduction()).isEqualTo("통합 테스트용 파티룸입니다");
        assertThat(loaded.getLinkDomain()).isEqualTo(LinkDomain.of("test-link"));
        assertThat(loaded.getPlaybackTimeLimit()).isEqualTo(PlaybackTimeLimit.ofMinutes(5));
        assertThat(loaded.getStageType()).isEqualTo(StageType.GENERAL);
        assertThat(loaded.getHostId()).isEqualTo(hostId);
        assertThat(loaded.isTerminated()).isFalse();
        assertThat(loaded.getPartyroomId()).isNotNull();
        assertThat(loaded.getStatus()).isEqualTo(PartyroomStatus.ACTIVE);
        assertThat(loaded.getDisplayFlag()).isEqualTo(DisplayFlag.NORMAL);
        assertThat(loaded.getLastActivityAt()).isNull();
    }

    @Test
    @DisplayName("findNonTerminatedHostRoom — 종료되지 않은 호스트의 파티룸을 조회한다")
    void findNonTerminatedHostRoomReturnsActiveRoom() {
        // given
        UserId hostId = new UserId(200L);
        PartyroomData partyroom = PartyroomData.create(
                "액티브 파티룸", "활성 파티룸",
                LinkDomain.of("active-link"), PlaybackTimeLimit.ofMinutes(10),
                StageType.GENERAL, hostId);
        partyroomRepository.save(partyroom);
        flushAndClear();

        // when
        Optional<PartyroomData> result = partyroomRepository.findNonTerminatedHostRoom(hostId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getHostId()).isEqualTo(hostId);
        assertThat(result.get().isTerminated()).isFalse();
    }

    @Test
    @DisplayName("findNonTerminatedHostRoom — 종료된 파티룸은 조회되지 않는다")
    void findNonTerminatedHostRoomExcludesTerminated() {
        // given
        UserId hostId = new UserId(300L);
        PartyroomData partyroom = PartyroomData.create(
                "종료된 파티룸", "종료됨",
                LinkDomain.of("terminated"), PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL, hostId);
        partyroom.terminate();
        partyroomRepository.save(partyroom);
        flushAndClear();

        // when
        Optional<PartyroomData> result = partyroomRepository.findNonTerminatedHostRoom(hostId);

        // then
        assertThat(result).isEmpty();
    }
}
