package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackStartedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackSnapshot;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #360: 구 PartyroomCounterListenerIT 재편 — crew_count 카운터 소멸 후 남은 책임인
 * lastActivityAt touch 를 잠근다. 크루 수 자체는 라이브 COUNT 가 진실
 * (AdminPartyroomQueryRepositoryImplIT / 로비 쿼리 IT 관할).
 */
class PartyroomActivityListenerIT extends AbstractIntegrationTest {

    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;

    /** saveAndFlush 로 별도 tx commit 되므로 AfterEach native delete 로 정리 (state pollution 방지). */
    private final List<Long> createdRoomIds = new ArrayList<>();

    private long createActiveRoom(long hostUid, String linkSuffix) {
        PartyroomData p = PartyroomData.create(
                "activity-test", "intro", LinkDomain.of("link-act-" + linkSuffix),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        long id = partyroomRepository.saveAndFlush(p).getId();
        createdRoomIds.add(id);
        return id;
    }

    @AfterEach
    void cleanupCreatedRooms() {
        if (createdRoomIds.isEmpty()) return;
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "DELETE FROM partyroom WHERE partyroom_id IN (:ids)")
                        .setParameter("ids", createdRoomIds)
                        .executeUpdate()
        );
        createdRoomIds.clear();
    }

    @Test
    @DisplayName("ENTER 이벤트 → lastActivityAt 갱신")
    void enter_touches_last_activity() {
        long roomId = createActiveRoom(3001L, "enter");

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(7777L), new UserId(3001L), AccessType.ENTER))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("EXIT 이벤트 → lastActivityAt 갱신 (ACTIVE 룸)")
    void exit_touches_last_activity() {
        long roomId = createActiveRoom(3002L, "exit");

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(8888L), new UserId(3002L), AccessType.EXIT))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("PlaybackStarted 이벤트 → lastActivityAt 갱신")
    void playback_started_touches_last_activity() {
        long roomId = createActiveRoom(3003L, "playback");

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PlaybackStartedEvent(
                        new PartyroomId(roomId), new CrewId(9999L),
                        new PlaybackSnapshot(0L, "", "", "", "", 0L)))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isNotNull();
    }

    @Test
    @DisplayName("TERMINATED 룸 이벤트 → touch 미적용 (ACTIVE 전용)")
    void terminated_room_not_touched() {
        long roomId = createActiveRoom(3004L, "terminated");
        transactionTemplate.executeWithoutResult(status -> {
            PartyroomData p = partyroomRepository.findById(roomId).orElseThrow();
            p.terminate();
            partyroomRepository.saveAndFlush(p);
        });

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId),
                        new CrewId(9999L), new UserId(3004L), AccessType.EXIT))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getLastActivityAt()).isNull(); // touch 는 ACTIVE 룸 전용
    }
}
