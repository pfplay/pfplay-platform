package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminPartyroomQueryRepository;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import com.pfplaybackend.api.administration.adapter.in.web.payload.response.AdminPartyroomListItemResponse.VirtualCrewSummary;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.CrewRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.DjRepository;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.CrewData;
import com.pfplaybackend.api.party.domain.entity.data.DjData;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.GradeType;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.CrewId;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PartyroomId;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.value.Nickname;
import com.pfplaybackend.api.virtualcrew.adapter.out.persistence.PartyroomVirtualCrewConfigRepository;
import com.pfplaybackend.api.virtualcrew.domain.entity.data.PartyroomVirtualCrewConfigData;
import com.pfplaybackend.api.virtualcrew.domain.enums.VirtualCrewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class AdminPartyroomQueryRepositoryImplIT extends AbstractIntegrationTest {

    @Autowired private AdminPartyroomQueryRepository queryRepository;
    @Autowired private PartyroomRepository partyroomRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CrewRepository crewRepository;
    @Autowired private DjRepository djRepository;
    @Autowired private PartyroomVirtualCrewConfigRepository virtualCrewConfigRepository;

    private Long aliceUid;
    private Long bobUid;

    @BeforeEach
    void setUp() {
        aliceUid = 7001L;
        bobUid = 7002L;
        seedHost(aliceUid, "alice@example.com", "Alice");
        seedHost(bobUid, "bob@example.com", "Bob");
    }

    /**
     * Seeds a UserAccount + Member + Profile (with nickname) trio for the given uid.
     * MemberData.userAccountId is a plain Long; ProfileData is attached via
     * initializeProfile(...) and contains the nickname embedded inside Bio.
     */
    private void seedHost(long uid, String email, String nickname) {
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(uid), email, "h"));
        MemberData member = MemberData.createForUserAccount(uid);
        ProfileData profile = ProfileData.builder()
                .userId(new UserId(uid))
                .nickname(new Nickname(nickname))
                .build();
        member.initializeProfile(profile);
        memberRepository.save(member);
    }

    private PartyroomData seedRoom(long hostUid, String title, PartyroomStatus status) {
        PartyroomData p = PartyroomData.create(
                title, "intro",
                LinkDomain.of("link-" + title),
                PlaybackTimeLimit.ofMinutes(5),
                StageType.GENERAL,
                new UserId(hostUid)
        );
        PartyroomData saved = partyroomRepository.saveAndFlush(p);
        if (status == PartyroomStatus.SUSPENDED) {
            saved.suspend();
            partyroomRepository.saveAndFlush(saved);
        } else if (status == PartyroomStatus.TERMINATED) {
            saved.terminate();
            partyroomRepository.saveAndFlush(saved);
        }
        return saved;
    }

    @Test
    @DisplayName("status null → TERMINATED 룸 제외 (ACTIVE+SUSPENDED만)")
    void default_excludes_terminated() {
        seedRoom(aliceUid, "active-room", PartyroomStatus.ACTIVE);
        seedRoom(aliceUid, "suspended-room", PartyroomStatus.SUSPENDED);
        seedRoom(bobUid, "terminated-room", PartyroomStatus.TERMINATED);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 20)
        );

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(AdminPartyroomListRow::status)
                .containsExactlyInAnyOrder(PartyroomStatus.ACTIVE, PartyroomStatus.SUSPENDED);
        assertThat(result.getContent()).extracting(AdminPartyroomListRow::status)
                .doesNotContain(PartyroomStatus.TERMINATED);
    }

    @Test
    @DisplayName("status=ACTIVE → ACTIVE 룸만 반환")
    void filter_active() {
        seedRoom(aliceUid, "active-room", PartyroomStatus.ACTIVE);
        seedRoom(aliceUid, "suspended-room", PartyroomStatus.SUSPENDED);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(PartyroomStatus.ACTIVE, null, null, null, null),
                PageRequest.of(0, 20)
        );

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).status()).isEqualTo(PartyroomStatus.ACTIVE);
        assertThat(result.getContent().get(0).title()).isEqualTo("active-room");
    }

    @Test
    @DisplayName("hostQuery=alice → email 부분 매칭으로 alice 룸만 반환")
    void filter_host_email() {
        seedRoom(aliceUid, "alice-room", PartyroomStatus.ACTIVE);
        seedRoom(bobUid, "bob-room", PartyroomStatus.ACTIVE);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, "alice"),
                PageRequest.of(0, 20)
        );

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).hostUserAccountId()).isEqualTo(aliceUid);
        assertThat(result.getContent().get(0).hostNickname()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("sort=title asc → 알파벳 오름차순 정렬")
    void sort_title() {
        seedRoom(aliceUid, "zeta-room", PartyroomStatus.ACTIVE);
        seedRoom(aliceUid, "alpha-room", PartyroomStatus.ACTIVE);
        seedRoom(aliceUid, "mid-room", PartyroomStatus.ACTIVE);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "title"))
        );

        assertThat(result.getContent()).extracting(AdminPartyroomListRow::title)
                .containsExactly("alpha-room", "mid-room", "zeta-room");
    }

    @Test
    @DisplayName("host의 profile 미보유 — partyroom row가 누락되지 않고 nickname=null로 반환")
    void host_without_profile_is_not_excluded() {
        long noProfileUid = 7099L;
        userAccountRepository.save(
                UserAccountData.createForLocalWithMandatoryChange(
                        new UserId(noProfileUid), "no-profile@example.com", "h"));
        // Member without profile attached — mirrors the V5-seeded super-admin
        // pre-finalizeSuperAdminProfile state.
        memberRepository.save(MemberData.createForUserAccount(noProfileUid));

        seedRoom(noProfileUid, "no-profile-room", PartyroomStatus.ACTIVE);
        seedRoom(aliceUid, "alice-room", PartyroomStatus.ACTIVE);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 20)
        );

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(2);
        AdminPartyroomListRow noProfileRow = result.getContent().stream()
                .filter(r -> r.hostUserAccountId().equals(noProfileUid))
                .findFirst()
                .orElseThrow();
        assertThat(noProfileRow.title()).isEqualTo("no-profile-room");
        assertThat(noProfileRow.hostNickname()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // P2 Task 2.2 — 가상 DJ 요약 조인 (어드민 전용, additive)
    // ─────────────────────────────────────────────────────────────────────────

    /** is_dummy 봇 계정 시드. */
    private void seedBotAccount(long uid, String email) {
        UserAccountData account = UserAccountData.createForLocal(new UserId(uid), email, "hash");
        account.markAsDummy();
        userAccountRepository.saveAndFlush(account);
    }

    /** 주어진 봇 uid 를 해당 룸의 활성 crew + DJ 로 배치. */
    private void placeBotDj(long roomId, long botUid, int orderNumber) {
        CrewData crew = crewRepository.saveAndFlush(CrewData.create(
                new PartyroomId(roomId), new UserId(botUid), GradeType.LISTENER, null));
        djRepository.saveAndFlush(DjData.create(
                new PartyroomId(roomId), null, new CrewId(crew.getId()), orderNumber));
    }

    /** 주어진 봇 uid 를 해당 룸의 비활성(is_active=false) crew + DJ 로 배치 (stale 시나리오). */
    private void placeInactiveBotDj(long roomId, long botUid, int orderNumber) {
        CrewData crew = crewRepository.saveAndFlush(CrewData.create(
                new PartyroomId(roomId), new UserId(botUid), GradeType.LISTENER, null));
        crew.deactivatePresence();
        crewRepository.saveAndFlush(crew);
        djRepository.saveAndFlush(DjData.create(
                new PartyroomId(roomId), null, new CrewId(crew.getId()), orderNumber));
    }

    private void seedManagedConfig(long roomId, int targetCount) {
        PartyroomVirtualCrewConfigData cfg = PartyroomVirtualCrewConfigData.create(roomId);
        cfg.applyManaged(targetCount, 1, null);
        virtualCrewConfigRepository.saveAndFlush(cfg);
    }

    @Test
    @DisplayName("가상DJ config(MANAGED) + 봇 DJ 2명 → virtualCrew.status=MANAGED, botDjCount=2")
    void virtual_crew_summary_with_managed_config_and_two_bots() {
        seedBotAccount(7201L, "bot-7201@vcrew-test.local");
        seedBotAccount(7202L, "bot-7202@vcrew-test.local");

        PartyroomData room = seedRoom(aliceUid, "managed-room", PartyroomStatus.ACTIVE);
        long roomId = room.getId();
        placeBotDj(roomId, 7201L, 1);
        placeBotDj(roomId, 7202L, 2);
        seedManagedConfig(roomId, 3);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 20));

        AdminPartyroomListRow row = result.getContent().stream()
                .filter(r -> r.partyroomId().equals(roomId))
                .findFirst().orElseThrow();
        VirtualCrewSummary vcrew = row.virtualCrew();
        assertThat(vcrew).isNotNull();
        assertThat(vcrew.status()).isEqualTo(VirtualCrewStatus.MANAGED);
        assertThat(vcrew.targetCount()).isEqualTo(3);
        assertThat(vcrew.botDjCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("가상DJ config 없는 룸 → virtualCrew=null")
    void virtual_crew_summary_null_without_config() {
        PartyroomData room = seedRoom(aliceUid, "no-config-room", PartyroomStatus.ACTIVE);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 20));

        AdminPartyroomListRow row = result.getContent().stream()
                .filter(r -> r.partyroomId().equals(room.getId()))
                .findFirst().orElseThrow();
        assertThat(row.virtualCrew()).isNull();
    }

    @Test
    @DisplayName("봇 DJ 가 없는 MANAGED config → botDjCount=0 (사람 DJ 는 봇으로 집계 안 됨)")
    void virtual_crew_summary_human_dj_not_counted_as_bot() {
        // alice(사람 host) 를 같은 룸의 DJ 로 배치 — is_dummy=false 이므로 botDjCount 에서 제외돼야 함.
        PartyroomData room = seedRoom(aliceUid, "human-dj-room", PartyroomStatus.ACTIVE);
        long roomId = room.getId();
        placeBotDj(roomId, aliceUid, 1); // aliceUid 는 봇이 아님(일반 host 계정)
        seedManagedConfig(roomId, 2);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 20));

        AdminPartyroomListRow row = result.getContent().stream()
                .filter(r -> r.partyroomId().equals(roomId))
                .findFirst().orElseThrow();
        assertThat(row.virtualCrew()).isNotNull();
        assertThat(row.virtualCrew().status()).isEqualTo(VirtualCrewStatus.MANAGED);
        assertThat(row.virtualCrew().botDjCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("비활성 crew 의 봇 DJ 는 botDjCount 에서 제외 (stale DJ 방어, canonical 정렬)")
    void inactive_crew_bot_dj_not_counted() {
        // 봇 계정 2개 준비: 한 명은 활성 crew, 한 명은 비활성(stale) crew.
        seedBotAccount(7301L, "bot-7301@vcrew-test.local");
        seedBotAccount(7302L, "bot-7302@vcrew-test.local");

        PartyroomData room = seedRoom(aliceUid, "stale-bot-room", PartyroomStatus.ACTIVE);
        long roomId = room.getId();
        placeBotDj(roomId, 7301L, 1);           // is_active=true  → 집계 포함
        placeInactiveBotDj(roomId, 7302L, 2);   // is_active=false → 집계 제외
        seedManagedConfig(roomId, 2);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 20));

        AdminPartyroomListRow row = result.getContent().stream()
                .filter(r -> r.partyroomId().equals(roomId))
                .findFirst().orElseThrow();
        assertThat(row.virtualCrew()).isNotNull();
        assertThat(row.virtualCrew().botDjCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("페이징 — page 0 size 1 → 컨텐츠 1개, totalElements=2")
    void paging() {
        seedRoom(aliceUid, "room-1", PartyroomStatus.ACTIVE);
        seedRoom(aliceUid, "room-2", PartyroomStatus.ACTIVE);

        Page<AdminPartyroomListRow> result = queryRepository.findAdminList(
                new AdminPartyroomListFilter(null, null, null, null, null),
                PageRequest.of(0, 1)
        );

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
    }
}
