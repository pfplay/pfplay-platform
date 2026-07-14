package com.pfplaybackend.api.party.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.AccessType;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.CrewAccessedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomClosedEvent;
import com.pfplaybackend.api.party.domain.event.PartyroomTerminatedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackDeactivatedEvent;
import com.pfplaybackend.api.party.domain.event.PlaybackStartedEvent;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackId;
import com.pfplaybackend.api.party.domain.value.PlaybackSnapshot;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartyroomCounterListenerIT extends AbstractIntegrationTest {

    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    /** 각 케이스가 생성한 partyroom id 모아서 AfterEach 에서 cleanup —
     *  본 IT 가 partyroomRepository.saveAndFlush 로 별도 tx commit 하므로
     *  @Transactional rollback 으로 정리되지 않음. cleanup 누락 시 다른 IT
     *  (CreateGeneralPartyRoomInvariantIntegrationTest 의 user_id 3001/3002/3005 등) 와
     *  state pollution 발생 — "이미 다른 파티룸의 호스트입니다" ForbiddenException 유발. */
    private final List<Long> createdRoomIds = new ArrayList<>();

    private long createActiveRoom(long hostUid, String linkSuffix) {
        PartyroomData p = PartyroomData.create(
                "listener-test", "intro", LinkDomain.of("link-" + linkSuffix),
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
        assertThat(reloaded.getActiveCrewCount()).isEqualTo(1);
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
        assertThat(reloaded.getActiveCrewCount()).isZero();
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
        assertThat(reloaded.getActiveCrewCount()).isZero();
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
    @DisplayName("PartyroomTerminatedEvent → crew_count = 0 reset")
    void terminated_resets_count() {
        long roomId = createActiveRoom(3010L, "term-event");
        // 사전에 +5
        for (int i = 0; i < 5; i++) {
            final long uid = 8000L + (long) i;
            transactionTemplate.executeWithoutResult(status ->
                    eventPublisher.publishEvent(new CrewAccessedEvent(
                            new PartyroomId(roomId), new CrewId(uid),
                            new UserId(uid), AccessType.ENTER))
            );
        }

        // FK fk_paa_administrator → administrator. terminate actor 를 실제 시드.
        final long adminUid = 990999L;
        userAccountRepository.saveAndFlush(
                UserAccountData.createForLocal(new UserId(adminUid), "counter-term-admin@x", "h"));
        Long adminId = administratorRepository
                .saveAndFlush(AdministratorData.createSuperAdmin(adminUid)).getAdministratorId();

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PartyroomTerminatedEvent(
                        new PartyroomId(roomId), adminId, "test reason"))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getActiveCrewCount()).isZero();
    }

    @Test
    @DisplayName("PartyroomClosedEvent → crew_count = 0 reset (host 자발 종료 일관성)")
    void closed_resets_count() {
        long roomId = createActiveRoom(3011L, "closed-event");
        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new CrewAccessedEvent(
                        new PartyroomId(roomId), new CrewId(9000L), new UserId(9000L), AccessType.ENTER))
        );

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new PartyroomClosedEvent(
                        new PartyroomId(roomId), new UserId(3011L), "closed-event"))
        );

        PartyroomData reloaded = partyroomRepository.findById(roomId).orElseThrow();
        assertThat(reloaded.getActiveCrewCount()).isZero();
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
        assertThat(reloaded.getActiveCrewCount()).isZero();
    }
}
