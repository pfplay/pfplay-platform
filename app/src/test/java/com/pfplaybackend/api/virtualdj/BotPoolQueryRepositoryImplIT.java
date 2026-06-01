package com.pfplaybackend.api.virtualdj;

import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.virtualdj.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualdj.application.dto.PoolPlacementRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BotPoolQueryRepositoryImpl 통합 테스트.
 *
 * <p>픽스처: is_dummy 봇 3명, 그 중 1명만 특정 파티룸의 활성 crew 로 배치.
 * <ul>
 *   <li>countBots() == 3</li>
 *   <li>countIdleBots() == 2</li>
 *   <li>findPlacements() → [partyroomId, botCount=1] 1건</li>
 * </ul>
 */
@Transactional
class BotPoolQueryRepositoryImplIT extends AbstractIntegrationTest {

    @Autowired private BotPoolQueryRepository botPoolQueryRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private PartyroomRepository partyroomRepository;

    private static final long BOT1_UID = 8001L;
    private static final long BOT2_UID = 8002L;
    private static final long BOT3_UID = 8003L;
    // 봇이 아닌 일반 유저 (집계에 포함되면 안 됨)
    private static final long HUMAN_UID = 8099L;
    // 파티룸 host uid (단순 FK 참조용, 실제 UserAccount row 필요)
    private static final long HOST_UID = 8100L;

    private Long partyroomId;

    @BeforeEach
    void setUp() {
        // host 계정 (파티룸 생성 FK 충족 목적)
        seedUserAccount(HOST_UID, "host-8100@bot-test.local", false);

        // 봇 3명 시드
        seedUserAccount(BOT1_UID, "bot-8001@bot-test.local", true);
        seedUserAccount(BOT2_UID, "bot-8002@bot-test.local", true);
        seedUserAccount(BOT3_UID, "bot-8003@bot-test.local", true);

        // 일반 유저 시드 (봇 집계에서 제외되어야 함)
        seedUserAccount(HUMAN_UID, "human-8099@bot-test.local", false);

        // 파티룸 1개 생성
        PartyroomData room = PartyroomData.create(
                "테스트 파티룸", "intro",
                LinkDomain.of("bot-test-room"),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL,
                new UserId(HOST_UID)
        );
        partyroomId = partyroomRepository.saveAndFlush(room).getId();

        // BOT1 만 해당 파티룸의 활성 crew 로 배치
        CrewData crew = CrewData.create(
                new PartyroomId(partyroomId), new UserId(BOT1_UID),
                GradeType.LISTENER, null);
        crewRepository.saveAndFlush(crew);

        // HUMAN 도 같은 방에 활성 crew 로 배치 (봇 집계에서 제외되어야 함)
        CrewData humanCrew = CrewData.create(
                new PartyroomId(partyroomId), new UserId(HUMAN_UID),
                GradeType.LISTENER, null);
        crewRepository.saveAndFlush(humanCrew);
    }

    private void seedUserAccount(long uid, String email, boolean dummy) {
        UserAccountData account = UserAccountData.createForLocal(
                new UserId(uid), email, "hash");
        if (dummy) {
            account.markAsDummy();
        }
        userAccountRepository.saveAndFlush(account);
    }

    @Test
    @DisplayName("countBots() — 탈퇴하지 않은 봇 3명 반환")
    void countBots_returns_3() {
        assertThat(botPoolQueryRepository.countBots()).isEqualTo(3L);
    }

    @Test
    @DisplayName("countIdleBots() — 활성 crew 없는 봇 2명 반환 (BOT2, BOT3)")
    void countIdleBots_returns_2() {
        assertThat(botPoolQueryRepository.countIdleBots()).isEqualTo(2L);
    }

    @Test
    @DisplayName("findPlacements() — 배치 파티룸 1건, botCount=1")
    void findPlacements_returns_one_room_with_one_bot() {
        List<PoolPlacementRow> placements = botPoolQueryRepository.findPlacements();

        assertThat(placements).hasSize(1);
        PoolPlacementRow row = placements.get(0);
        assertThat(row.partyroomId()).isEqualTo(partyroomId);
        assertThat(row.partyroomTitle()).isEqualTo("테스트 파티룸");
        assertThat(row.botCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("findPlacements() — 봇이 없는 파티룸은 결과에 포함되지 않는다")
    void findPlacements_excludes_rooms_without_bots() {
        // 봇 없는 별도 파티룸 생성 (HUMAN만 있는 방은 이미 위에서 고려됨)
        // 완전히 빈 새 파티룸 추가
        PartyroomData emptyRoom = PartyroomData.create(
                "빈 파티룸", "intro",
                LinkDomain.of("empty-room-bot-test"),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL,
                new UserId(HOST_UID)
        );
        partyroomRepository.saveAndFlush(emptyRoom);

        List<PoolPlacementRow> placements = botPoolQueryRepository.findPlacements();

        // 빈 파티룸은 결과에 없어야 함 — 1건 유지
        assertThat(placements).hasSize(1);
        assertThat(placements.get(0).partyroomId()).isEqualTo(partyroomId);
    }

    @Test
    @DisplayName("findPlacements() — TERMINATED 파티룸의 봇은 결과에 포함되지 않는다")
    void findPlacements_excludes_bots_in_terminated_room() {
        // TERMINATED 파티룸을 builder 로 직접 생성 (도메인 이벤트 발행 없이 상태 주입)
        PartyroomData terminatedRoom = PartyroomData.builder()
                .title("종료된 파티룸")
                .introduction("intro")
                .linkDomain(LinkDomain.of("terminated-room-bot-test"))
                .playbackTimeLimit(PlaybackTimeLimit.ofMinutes(5))
                .stageType(StageType.GENERAL)
                .hostId(new UserId(HOST_UID))
                .status(PartyroomStatus.TERMINATED)
                .activeCrewCount(0)
                .build();
        Long terminatedRoomId = partyroomRepository.saveAndFlush(terminatedRoom).getId();

        // BOT2 를 TERMINATED 파티룸의 활성 crew 로 배치
        CrewData bot2Crew = CrewData.create(
                new PartyroomId(terminatedRoomId), new UserId(BOT2_UID),
                GradeType.LISTENER, null);
        crewRepository.saveAndFlush(bot2Crew);

        List<PoolPlacementRow> placements = botPoolQueryRepository.findPlacements();

        // ACTIVE 파티룸(partyroomId, BOT1)만 결과에 포함되어야 함
        assertThat(placements).hasSize(1);
        assertThat(placements.get(0).partyroomId()).isEqualTo(partyroomId);
        assertThat(placements.get(0).botCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("countBots() — 탈퇴한 봇은 제외한다")
    void countBots_excludes_withdrawn_bots() {
        // BOT3 탈퇴
        UserAccountData bot3 = userAccountRepository.findById(new UserId(BOT3_UID)).orElseThrow();
        bot3.withdraw(null);
        userAccountRepository.saveAndFlush(bot3);

        // 탈퇴 후: 봇 2명만 유효
        assertThat(botPoolQueryRepository.countBots()).isEqualTo(2L);
        assertThat(botPoolQueryRepository.countIdleBots()).isEqualTo(1L);
    }
}
