package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminPartyroomQueryRepository;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListFilter;
import com.pfplaybackend.api.administration.application.dto.AdminPartyroomListRow;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.party.adapter.out.persistence.PartyroomRepository;
import com.pfplaybackend.api.party.domain.entity.data.PartyroomData;
import com.pfplaybackend.api.party.domain.enums.PartyroomStatus;
import com.pfplaybackend.api.party.domain.enums.StageType;
import com.pfplaybackend.api.party.domain.value.LinkDomain;
import com.pfplaybackend.api.party.domain.value.PlaybackTimeLimit;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.value.Nickname;
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
