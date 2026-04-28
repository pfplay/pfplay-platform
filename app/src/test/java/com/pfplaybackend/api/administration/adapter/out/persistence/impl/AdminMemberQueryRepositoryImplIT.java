package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminMemberQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminMemberSummaryRow;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.value.Nickname;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 IT: A-1 search end-to-end against TestContainers MySQL.
 *
 * <p>Seeds 5 members + their UserAccounts with varying email/tier/createdAt, plus 5
 * user_activity_log rows for one member (and 0 for others) to exercise both the
 * filter (email LIKE / tier / 가입일 range) + sort (created_at asc/desc, last_activity_desc) +
 * pagination paths in {@link AdminMemberQueryRepository#search}.
 *
 * <p>JPA auditing auto-populates {@code created_at}; for deterministic 가입일 + last_activity
 * ordering we override created_at via native UPDATE after persist.
 */
class AdminMemberQueryRepositoryImplIT extends AbstractIntegrationTest {

    private static final long UID_FOO_1 = 8001L;
    private static final long UID_FOO_2 = 8002L;
    private static final long UID_BAR_1 = 8003L;
    private static final long UID_BAR_2 = 8004L;
    private static final long UID_BAR_3 = 8005L;

    @Autowired AdminMemberQueryRepository adminMemberQueryRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager entityManager;

    private Long memberIdFoo1;
    private Long memberIdFoo2;
    private Long memberIdBar1;
    private Long memberIdBar2;
    private Long memberIdBar3;

    @BeforeEach
    void seed() {
        // FOO 1: tier FM, createdAt 2025-12-01, with 5 activity rows.
        memberIdFoo1 = seedMember(UID_FOO_1, "foo1@g4it.local", "FooOne",
                AuthorityTier.FM, LocalDateTime.of(2025, 12, 1, 0, 0));
        seedActivities(UID_FOO_1, LocalDateTime.of(2026, 4, 28, 12, 0), 5);

        // FOO 2: tier AM, createdAt 2026-01-15, no activities.
        memberIdFoo2 = seedMember(UID_FOO_2, "foo2@g4it.local", "FooTwo",
                AuthorityTier.AM, LocalDateTime.of(2026, 1, 15, 0, 0));

        // BAR 1: tier FM, createdAt 2026-03-10, no activities.
        memberIdBar1 = seedMember(UID_BAR_1, "bar1@g4it.local", "BarOne",
                AuthorityTier.FM, LocalDateTime.of(2026, 3, 10, 0, 0));

        // BAR 2: tier AM, createdAt 2026-06-20, no activities.
        memberIdBar2 = seedMember(UID_BAR_2, "bar2@g4it.local", "BarTwo",
                AuthorityTier.AM, LocalDateTime.of(2026, 6, 20, 0, 0));

        // BAR 3: tier GT, createdAt 2026-12-25, no activities.
        memberIdBar3 = seedMember(UID_BAR_3, "bar3@g4it.local", "BarThree",
                AuthorityTier.GT, LocalDateTime.of(2026, 12, 25, 0, 0));
    }

    @AfterEach
    void cleanup() {
        // V5 super-admin row(member_id=1, user_id=1)을 보존하기 위해 native delete로 SEED row만 제거.
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                            "DELETE FROM user_activity_log WHERE user_account_id IN (:uids)")
                    .setParameter("uids",
                            java.util.List.of(UID_FOO_1, UID_FOO_2, UID_BAR_1, UID_BAR_2, UID_BAR_3))
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM member WHERE user_account_id IN (:uids)")
                    .setParameter("uids",
                            java.util.List.of(UID_FOO_1, UID_FOO_2, UID_BAR_1, UID_BAR_2, UID_BAR_3))
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM user_profile WHERE user_id IN (:uids)")
                    .setParameter("uids",
                            java.util.List.of(UID_FOO_1, UID_FOO_2, UID_BAR_1, UID_BAR_2, UID_BAR_3))
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM user_account WHERE user_id IN (:uids)")
                    .setParameter("uids",
                            java.util.List.of(UID_FOO_1, UID_FOO_2, UID_BAR_1, UID_BAR_2, UID_BAR_3))
                    .executeUpdate();
        });
    }

    private Long seedMember(long uid, String email, String nickname,
                            AuthorityTier tier, LocalDateTime createdAt) {
        return transactionTemplate.execute(status -> {
            UserAccountData ua = UserAccountData.createForLocal(new UserId(uid), email, "h");
            userAccountRepository.saveAndFlush(ua);
            // override JPA auditing-set createdAt — controlled value for date-range tests.
            entityManager.createNativeQuery(
                            "UPDATE user_account SET created_at = :ts WHERE user_id = :uid")
                    .setParameter("ts", createdAt)
                    .setParameter("uid", uid)
                    .executeUpdate();

            MemberData member = MemberData.createForUserAccount(uid);
            ProfileData profile = ProfileData.builder()
                    .userId(new UserId(uid))
                    .nickname(new Nickname(nickname))
                    .introduction("intro-" + uid)
                    .build();
            member.initializeProfile(profile);
            MemberData saved = memberRepository.saveAndFlush(member);
            // bump tier to the desired value (factory always sets AM by default).
            if (tier != AuthorityTier.AM) {
                entityManager.createNativeQuery(
                                "UPDATE member SET authority_tier = :tier WHERE member_id = :mid")
                        .setParameter("tier", tier.name())
                        .setParameter("mid", saved.getMemberId())
                        .executeUpdate();
            }
            return saved.getMemberId();
        });
    }

    private void seedActivities(long uid, LocalDateTime base, int count) {
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                userActivityLogRepository.save(UserActivityLogData.of(
                        uid,
                        UserActivityEventType.PARTYROOM_ENTERED,
                        (long) (i + 1),
                        JsonMetadata.empty(),
                        base.plusSeconds(i)));
            }
        });
    }

    @Test
    @DisplayName("search: email LIKE 'foo' → 2 members")
    void search_filters_by_email_like() {
        AdminMemberListQuery query = new AdminMemberListQuery(
                "foo", null, null, null, AdminMemberListQuery.SORT_CREATED_AT_DESC);
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(query, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(AdminMemberSummaryRow::memberId)
                .containsExactlyInAnyOrder(memberIdFoo1, memberIdFoo2);
    }

    @Test
    @DisplayName("search: tier=FM → 2 members")
    void search_filters_by_tier() {
        // V5 super-admin이 FM tier일 수 있으므로 example.com email filter로 SEED만 격리.
        AdminMemberListQuery query = new AdminMemberListQuery(
                "g4it.local", AuthorityTier.FM, null, null, AdminMemberListQuery.SORT_CREATED_AT_DESC);
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(query, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(AdminMemberSummaryRow::memberId)
                .containsExactlyInAnyOrder(memberIdFoo1, memberIdBar1);
        assertThat(result.getContent()).allSatisfy(r ->
                assertThat(r.authorityTier()).isEqualTo(AuthorityTier.FM));
    }

    @Test
    @DisplayName("search: 가입일 range — 2026-02-01 ~ 2026-07-01 → 2 members (BAR1, BAR2)")
    void search_filters_by_date_range() {
        AdminMemberListQuery query = new AdminMemberListQuery(
                "g4it.local", null,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 7, 1),
                AdminMemberListQuery.SORT_CREATED_AT_ASC);
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(query, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(AdminMemberSummaryRow::memberId)
                .containsExactly(memberIdBar1, memberIdBar2);
    }

    @Test
    @DisplayName("search: sort=created_at_desc → createdAt DESC monotonic")
    void sort_created_at_desc() {
        // V5-seeded super-admin row (member_id=1)도 함께 포함되므로 example.com 필터로 격리.
        AdminMemberListQuery query = new AdminMemberListQuery(
                "g4it.local", null, null, null, AdminMemberListQuery.SORT_CREATED_AT_DESC);
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(query, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(5);
        // 가장 최근 가입자(BAR3, 2026-12-25)가 첫번째, 가장 오래된(FOO1, 2025-12-01)이 마지막.
        assertThat(result.getContent().get(0).memberId()).isEqualTo(memberIdBar3);
        assertThat(result.getContent().get(4).memberId()).isEqualTo(memberIdFoo1);
        // monotonic DESC.
        for (int i = 0; i < result.getContent().size() - 1; i++) {
            LocalDateTime cur = result.getContent().get(i).createdAt();
            LocalDateTime next = result.getContent().get(i + 1).createdAt();
            assertThat(cur).isAfterOrEqualTo(next);
        }
    }

    @Test
    @DisplayName("search: sort=last_activity_desc — 활동 0건 member도 포함, 활동 있는 member 우선")
    void sort_last_activity_desc_includes_inactive_members() {
        AdminMemberListQuery query = new AdminMemberListQuery(
                "g4it.local", null, null, null, AdminMemberListQuery.SORT_LAST_ACTIVITY_DESC);
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(query, PageRequest.of(0, 50));

        // 활동 0건 4명 + 활동 5건 1명 = 5명 모두 포함.
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getContent()).hasSize(5);

        // FOO1 (활동 시각 2026-04-28 12:00:04) vs BAR3 (createdAt 2026-12-25 fallback).
        // BAR3 createdAt > FOO1 last activity → BAR3가 먼저 정렬되어야 함.
        // (실제 sort는 COALESCE(MAX(occurred_at), created_at) DESC.)
        assertThat(result.getContent()).extracting(AdminMemberSummaryRow::memberId)
                .contains(memberIdFoo1, memberIdFoo2, memberIdBar1, memberIdBar2, memberIdBar3);
    }

    @Test
    @DisplayName("search: pagination — page=1 size=2 → second page (3rd row)")
    void pagination_works() {
        AdminMemberListQuery query = new AdminMemberListQuery(
                "g4it.local", null, null, null, AdminMemberListQuery.SORT_CREATED_AT_ASC);
        Pageable pageable = PageRequest.of(1, 2);
        Page<AdminMemberSummaryRow> result = adminMemberQueryRepository.search(query, pageable);

        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getContent()).hasSize(2);
        // ASC 정렬: 1) FOO1(2025-12) 2) FOO2(2026-01) 3) BAR1(2026-03) 4) BAR2(2026-06) 5) BAR3(2026-12)
        // page=1, size=2 → 3rd & 4th = BAR1, BAR2.
        assertThat(result.getContent()).extracting(AdminMemberSummaryRow::memberId)
                .containsExactly(memberIdBar1, memberIdBar2);
    }
}
