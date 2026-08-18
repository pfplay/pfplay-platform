package com.pfplaybackend.api.party.application.service;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.party.adapter.out.persistence.DjQueueRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomPlaybackRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.application.port.out.PlaylistCommandPort;
import com.pfplaybackend.api.party.application.port.out.PlaylistQueryPort;
import com.pfplaybackend.api.party.application.port.out.UserProfileQueryPort;
import com.pfplaybackend.api.party.domain.entity.data.DjQueueData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomPlaybackData;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.event.SessionSupersededEvent;
import com.pfplaybackend.api.party.domain.value.CountryCode;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.application.dto.shared.ProfileSettingDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * #369 멀티 디바이스 세션 승계 — 밀어냄 발생 시 SessionSupersededEvent 발행 검증.
 *
 * <p>같은 유저가 다른 방으로 입장해 기존 활성 crew 가 auto-exit 될 때만 이벤트가 발행되어야 하며,
 * 최초 입장·같은 방 재입장(멀티탭) 등 밀어냄이 없는 경로에서는 발행되지 않아야 한다. 이벤트는
 * 밀려난 유저의 개인 큐 알림(SESSION_SUPERSEDED)을 트리거하는 신호다.
 */
@RecordApplicationEvents
class SessionSupersededEventIT extends AbstractIntegrationTest {

    @Autowired private PartyroomAccessCommandService accessCommandService;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private PartyroomPlaybackRepository partyroomPlaybackRepository;
    @Autowired private DjQueueRepository djQueueRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEvents applicationEvents;

    private final List<Long> createdRoomIds = new ArrayList<>();

    @MockBean private UserProfileQueryPort userProfileQueryPort;
    @MockBean private PlaylistQueryPort playlistQueryPort;
    @MockBean private PlaylistCommandPort playlistCommandPort;

    @BeforeEach
    void stubBoundaryPorts() {
        lenient().when(userProfileQueryPort.getUsersProfileSetting(any()))
                .thenAnswer(inv -> {
                    List<UserId> ids = inv.getArgument(0);
                    Map<UserId, ProfileSettingDto> result = new java.util.HashMap<>();
                    for (UserId id : ids) {
                        result.put(id, mock(ProfileSettingDto.class));
                    }
                    return result;
                });
        lenient().when(playlistQueryPort.isOwnedBy(anyLong(), anyLong())).thenReturn(true);
        lenient().when(playlistQueryPort.isEmptyPlaylist(anyLong())).thenReturn(false);
    }

    private long createActiveRoom(long hostUid, String linkSuffix) {
        PartyroomData p = PartyroomData.create(
                "supersede-it", "intro", LinkDomain.of("link-supersede-" + linkSuffix),
                PlaybackTimeLimit.ofMinutes(5), StageType.GENERAL,
                new UserId(hostUid));
        long id = partyroomRepository.saveAndFlush(p).getId();
        partyroomPlaybackRepository.save(PartyroomPlaybackData.createFor(new PartyroomId(id)));
        djQueueRepository.save(DjQueueData.createFor(new PartyroomId(id)));
        createdRoomIds.add(id);
        return id;
    }

    private void enterAs(UserId user, long roomId) {
        ThreadLocalContext.setContext(new AuthContext(user, AuthorityTier.GT));
        accessCommandService.tryEnter(new PartyroomId(roomId), CountryCode.of("KR"));
    }

    @AfterEach
    void cleanup() {
        ThreadLocalContext.clearContext();
        if (createdRoomIds.isEmpty()) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM crew WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dj WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dj_queue WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM partyroom_playback WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM partyroom WHERE partyroom_id IN (:ids)")
                    .setParameter("ids", createdRoomIds).executeUpdate();
        });
        createdRoomIds.clear();
    }

    @Test
    @DisplayName("다른 방으로 밀어냄 → SessionSupersededEvent 발행 + 밀려난 crew is_active=0/exited_at (★#369)")
    void supersede_publishes_event_and_deactivates_prior_crew() {
        long roomX = createActiveRoom(9001L, "x");
        long roomY = createActiveRoom(9002L, "y");
        UserId user = new UserId(8801L);

        enterAs(user, roomX);
        enterAs(user, roomY); // 밀어냄: X 에서 auto-exit → Y 활성

        List<SessionSupersededEvent> events =
                applicationEvents.stream(SessionSupersededEvent.class).toList();
        assertThat(events).hasSize(1);
        SessionSupersededEvent event = events.get(0);
        assertThat(event.getUserId()).isEqualTo(user);
        assertThat(event.getSupersededPartyroomId()).isEqualTo(new PartyroomId(roomX));
        assertThat(event.getNewPartyroomId()).isEqualTo(new PartyroomId(roomY));
        assertThat(event.getOccurredAtEpochMilli()).isPositive();

        // 회귀: 밀려난 crew 는 is_active=0 + exited_at 기록 (기존 동작 보존)
        Number activeInX = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM crew WHERE partyroom_id = :id AND user_id = :uid AND is_active = 1")
                .setParameter("id", roomX).setParameter("uid", 8801L).getSingleResult();
        Number exitedRecorded = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM crew WHERE partyroom_id = :id AND user_id = :uid AND exited_at IS NOT NULL")
                .setParameter("id", roomX).setParameter("uid", 8801L).getSingleResult();
        assertThat(activeInX.longValue()).as("밀려난 crew 는 비활성화").isZero();
        assertThat(exitedRecorded.longValue()).as("밀려난 crew 는 exited_at 기록").isEqualTo(1L);
    }

    @Test
    @DisplayName("최초 입장(밀어냄 없음) → SessionSupersededEvent 미발행 (#369)")
    void first_entry_does_not_publish_event() {
        long roomX = createActiveRoom(9003L, "first");
        UserId user = new UserId(8802L);

        enterAs(user, roomX);

        assertThat(applicationEvents.stream(SessionSupersededEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("같은 방 재입장(멀티탭/재연결) → SessionSupersededEvent 미발행 (#369)")
    void same_room_re_entry_does_not_publish_event() {
        long roomX = createActiveRoom(9004L, "same");
        UserId user = new UserId(8803L);

        enterAs(user, roomX);
        enterAs(user, roomX); // 같은 방 재입장 — 밀어냄 아님

        assertThat(applicationEvents.stream(SessionSupersededEvent.class)).isEmpty();
    }
}
