package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackDeactivatedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackStartedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackSnapshot;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomCounterListenerIT extends AbstractIntegrationTest {

    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;

    private long createActiveRoom(long hostUid, String linkSuffix) {
        PartyroomData p = PartyroomData.create(
                "listener-test", "intro", LinkDomain.of("link-" + linkSuffix),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        return partyroomRepository.saveAndFlush(p).getId();
    }

    @Test
    @DisplayName("ENTER 이벤트 → crew_count +1, lastActivityAt 갱신")
    void enter_increments() {
        long roomId = createActiveRoom(3001L, "enter");

        // AFTER_COMMIT phase가 fire되도록 트랜잭션 안에서 publish
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(7777L), new UserId(3001L), AccessType.ENTER))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isEqualTo(1);
        assertThat(reloaded.getLastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("EXIT 이벤트 → crew_count -1")
    void exit_decrements() {
        long roomId = createActiveRoom(3002L, "exit");
        // 사전 +1
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(8888L), new UserId(3002L), AccessType.ENTER))
        );

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(8888L), new UserId(3002L), AccessType.EXIT))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isZero();
    }

    @Test
    @DisplayName("PlaybackStartedEvent → lastActivityAt 갱신 (crew_count는 변함 없음)")
    void playback_started_touches() {
        long roomId = createActiveRoom(3003L, "pstart");

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PlaybackStartedEvent(
                        new PartyroomId(roomId),
                        new CrewId(9999L),
                        new PlaybackSnapshot(0L, "", "", "", "", 0L)
                ))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isNotNull();
        assertThat(reloaded.getCrewCount()).isZero();
    }

    @Test
    @DisplayName("PlaybackDeactivatedEvent → lastActivityAt 갱신")
    void playback_deactivated_touches() {
        long roomId = createActiveRoom(3004L, "pdeact");

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PlaybackDeactivatedEvent(
                        new PartyroomId(roomId),
                        new PlaybackId(1111L), new CrewId(2222L)))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("ENTER 이벤트가 TERMINATED 룸에 도착하면 crew_count 변화 없음 (WARN 로그)")
    void enter_terminated_room_noop() {
        long roomId = createActiveRoom(3005L, "term");
        // 룸 종료
        transactionTemplate.executeWithoutResult(status -> {
            PartyroomData p = partyroomRepository.findById(roomId).orElseThrow();
            p.terminate();
            partyroomRepository.saveAndFlush(p);
        });

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(3333L), new UserId(3005L), AccessType.ENTER))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getCrewCount()).isZero();
    }
}
