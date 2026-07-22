package com.pfplaybackend.api.virtualcrew;

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
import com.pfplaybackend.api.avatar.domain.value.AvatarBodyUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarFaceUri;
import com.pfplaybackend.api.avatar.domain.value.AvatarIconUri;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserProfileRepository;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPersonaAssignmentRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.BotPoolQueryRepository;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.VirtualPersonaRepository;
import com.pfplaybackend.api.virtualcrew.application.dto.BotCandidate;
import com.pfplaybackend.api.virtualcrew.application.dto.BotRosterRow;
import com.pfplaybackend.api.virtualcrew.application.dto.PoolPlacementRow;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.BotPersonaAssignmentData;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.VirtualPersonaData;
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
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private VirtualPersonaRepository virtualPersonaRepository;
    @Autowired private BotPersonaAssignmentRepository botPersonaAssignmentRepository;

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

    private void seedProfile(long uid, String nickname, String bodyUri, String iconUri) {
        ProfileData profile = ProfileData.builder()
                .userId(new UserId(uid))
                .nickname(new com.pfplaybackend.api.user.domain.value.Nickname(nickname))
                .avatarBodyUri(new AvatarBodyUri(bodyUri))
                .avatarFaceUri(new AvatarFaceUri(""))
                .avatarIconUri(new AvatarIconUri(iconUri))
                .build();
        userProfileRepository.saveAndFlush(profile);
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
    @DisplayName("findRoster() — 봇의 닉네임/아바타/배치룸 반환, idle 봇은 placementPartyroomId null")
    void findRoster_returns_bot_identity_avatar_and_placement() {
        // 봇 프로필 시드: BOT1(배치됨), BOT2(idle). HUMAN(비봇)도 프로필 시드해 is_dummy 게이트로 제외됨을 검증.
        seedProfile(BOT1_UID, "봇하나", "https://cdn/body1.png", "https://cdn/icon1.png");
        seedProfile(BOT2_UID, "봇둘", "https://cdn/body2.png", "https://cdn/icon2.png");
        seedProfile(HUMAN_UID, "사람", "https://cdn/bodyH.png", "https://cdn/iconH.png");

        List<BotRosterRow> roster = botPoolQueryRepository.findRoster();

        // BOT1·BOT2 만 (프로필 없는 BOT3 는 inner join 으로 빠짐, HUMAN 은 비봇).
        assertThat(roster).hasSize(2);
        assertThat(roster).allSatisfy(r -> {
            assertThat(r.nickname()).isNotBlank();
            assertThat(r.avatarBodyUri()).isNotNull();
        });

        // 비봇(사람)이 로스터에 섞이지 않는다.
        assertThat(roster).noneSatisfy(r -> assertThat(r.userId()).isEqualTo(HUMAN_UID));

        // uid 오름차순 → BOT1 먼저(배치됨), BOT2 다음(idle).
        BotRosterRow placed = roster.get(0);
        assertThat(placed.userId()).isEqualTo(BOT1_UID);
        assertThat(placed.placementPartyroomId()).isEqualTo(partyroomId);
        assertThat(placed.placementPartyroomTitle()).isEqualTo("테스트 파티룸");
        assertThat(placed.avatarBodyUri()).isEqualTo("https://cdn/body1.png");

        BotRosterRow idle = roster.get(1);
        assertThat(idle.userId()).isEqualTo(BOT2_UID);
        assertThat(idle.placementPartyroomId()).isNull();
        assertThat(idle.placementPartyroomTitle()).isNull();
    }

    private Long seedPersona(String name) {
        VirtualPersonaData persona = virtualPersonaRepository.saveAndFlush(
                VirtualPersonaData.create(name, "instruction"));
        return persona.getId();
    }

    private void seedAssignment(long botUid, long personaId) {
        botPersonaAssignmentRepository.saveAndFlush(
                BotPersonaAssignmentData.create(botUid, personaId));
    }

    @Test
    @DisplayName("findActivePersonaBotsInRoom() — 페르소나 매핑된 활성 crew 봇만 반환")
    void findActivePersonaBotsInRoom_returns_only_personaed_active_bots() {
        Long personaId = seedPersona("DJ봇");
        // BOT1: 방의 활성 crew + 페르소나 매핑 → 후보
        seedAssignment(BOT1_UID, personaId);
        // HUMAN: 같은 방 활성 crew + 페르소나 매핑이지만 비봇 → 제외
        seedAssignment(HUMAN_UID, personaId);

        List<BotCandidate> candidates =
                botPoolQueryRepository.findActivePersonaBotsInRoom(new PartyroomId(partyroomId));

        assertThat(candidates).hasSize(1);
        BotCandidate c = candidates.get(0);
        assertThat(c.botUserId()).isEqualTo(BOT1_UID);
        assertThat(c.personaId()).isEqualTo(personaId);
        assertThat(c.crewId()).isNotNull();
        // crewId 는 BOT1 의 활성 crew row PK 와 일치한다.
        CrewData bot1Crew = crewRepository.findAll().stream()
                .filter(cr -> cr.getUserId().getUid().equals(BOT1_UID) && cr.isActive())
                .findFirst().orElseThrow();
        assertThat(c.crewId()).isEqualTo(bot1Crew.getId());
    }

    @Test
    @DisplayName("findActivePersonaBotsInRoom() — 페르소나 없는 봇은 제외한다")
    void findActivePersonaBotsInRoom_excludes_bot_without_persona() {
        // BOT1 은 방의 활성 crew 이지만 어떤 assignment 도 시드하지 않는다.
        List<BotCandidate> candidates =
                botPoolQueryRepository.findActivePersonaBotsInRoom(new PartyroomId(partyroomId));

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("findActivePersonaBotsInRoom() — 다른 방/비활성 crew 봇은 제외한다")
    void findActivePersonaBotsInRoom_excludes_other_room_and_inactive_crew() {
        Long personaId = seedPersona("DJ봇");
        // BOT2 는 페르소나 매핑이 있지만 어느 방에도 활성 crew 가 없는 idle 봇 → 제외.
        seedAssignment(BOT2_UID, personaId);

        List<BotCandidate> candidates =
                botPoolQueryRepository.findActivePersonaBotsInRoom(new PartyroomId(partyroomId));

        // BOT1 은 페르소나 없음, BOT2 는 활성 crew 없음 → 둘 다 후보 아님.
        assertThat(candidates).isEmpty();
    }

    /**
     * 방 안에 활성 봇 crew 3개(2 DJ봇 + 1 리스너봇)를 시드한다.
     *
     * <p>이 쿼리는 역할/페르소나 비게이트이므로 "DJ봇 vs 리스너봇" 은 crew 등급으로 표현되지 않는다
     * (DJ 여부는 별도 DJ 큐 개념). 대신 페르소나 매핑 유무로 구분을 모사한다: BOT1·BOT2 는 페르소나
     * 매핑(=DJ 후보 성격), BOT3 는 매핑 없음(=순수 리스너봇). 셋 다 방의 활성 crew·is_dummy 계정이므로
     * countActiveBotCrewsInRoom 은 페르소나 유무와 무관하게 3을 반환해야 한다.
     *
     * <p>enteredAt 을 명시로 제어해 입장순 정렬을 검증한다: BOT2(과거) &lt; BOT1(setUp, ~now) &lt; BOT3(미래).
     */
    private void seedActiveBotCrew(long uid, java.time.LocalDateTime enteredAt) {
        CrewData crew = CrewData.create(
                new PartyroomId(partyroomId), new UserId(uid),
                GradeType.LISTENER, null, enteredAt);
        crewRepository.saveAndFlush(crew);
    }

    @Test
    @DisplayName("countActiveBotCrewsInRoom() — DJ봇2+리스너봇1=활성 봇 crew 3 (페르소나 비게이트)")
    void countActiveBotCrewsInRoom_counts_all_active_bot_crews_persona_agnostic() {
        // BOT1 은 setUp 에서 이미 방의 활성 crew. BOT2·BOT3 를 활성 crew 로 추가 → 봇 crew 3개.
        seedActiveBotCrew(BOT2_UID, java.time.LocalDateTime.now().minusMinutes(10));
        seedActiveBotCrew(BOT3_UID, java.time.LocalDateTime.now().plusMinutes(10));

        // 페르소나는 일부(BOT1·BOT2)에만 배정 — 비게이트 검증용. BOT3 는 페르소나 없음.
        Long personaId = seedPersona("DJ봇");
        seedAssignment(BOT1_UID, personaId);
        seedAssignment(BOT2_UID, personaId);

        // 페르소나 게이트 쿼리는 2만 반환하지만, 역할 비게이트 카운트는 3이어야 한다.
        assertThat(botPoolQueryRepository.findActivePersonaBotsInRoom(new PartyroomId(partyroomId)))
                .hasSize(2);
        assertThat(botPoolQueryRepository.countActiveBotCrewsInRoom(new PartyroomId(partyroomId)))
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("findActiveBotCrewUserIdsByJoinedDesc() — 봇 crew 3개, 최근 입장 우선, 비봇 제외")
    void findActiveBotCrewUserIdsByJoinedDesc_returns_bots_recent_first() {
        seedActiveBotCrew(BOT2_UID, java.time.LocalDateTime.now().minusMinutes(10));
        seedActiveBotCrew(BOT3_UID, java.time.LocalDateTime.now().plusMinutes(10));

        List<UserId> uids = botPoolQueryRepository
                .findActiveBotCrewUserIdsByJoinedDesc(new PartyroomId(partyroomId));

        // 봇 3개만 (HUMAN 은 같은 방 활성 crew 이지만 비봇 → 제외).
        assertThat(uids).hasSize(3);
        assertThat(uids.stream().map(UserId::getUid))
                .containsExactly(BOT3_UID, BOT1_UID, BOT2_UID); // enteredAt DESC
        assertThat(uids.stream().map(UserId::getUid)).doesNotContain(HUMAN_UID);
    }

    @Test
    @DisplayName("countActiveBotCrewsInRoom() — 다른 방/비활성 crew 봇·탈퇴 봇은 제외한다")
    void countActiveBotCrewsInRoom_excludes_out_of_room_inactive_and_withdrawn() {
        // BOT2 를 방의 활성 crew 로 추가 → 방 안 봇 crew 2개 (BOT1, BOT2).
        seedActiveBotCrew(BOT2_UID, java.time.LocalDateTime.now());
        assertThat(botPoolQueryRepository.countActiveBotCrewsInRoom(new PartyroomId(partyroomId)))
                .isEqualTo(2L);

        // BOT2 탈퇴 → 봇 게이트에서 제외되어 1로 감소.
        UserAccountData bot2 = userAccountRepository.findById(new UserId(BOT2_UID)).orElseThrow();
        bot2.withdraw(null);
        userAccountRepository.saveAndFlush(bot2);
        assertThat(botPoolQueryRepository.countActiveBotCrewsInRoom(new PartyroomId(partyroomId)))
                .isEqualTo(1L);
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
