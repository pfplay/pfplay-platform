package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminGuestQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestDetailRow;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestSummaryRow;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.GuestRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.GuestData;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for {@link AdminGuestQueryRepository} end-to-end against TestContainers MySQL.
 *
 * <p>AdminMemberQueryRepositoryImplIT 패턴 동형. Seed 5 guests + 1 명 활동 5건, 나머지 0건.
 * tier filter 부재만 차이 (guest 는 항상 GT).
 *
 * <p>JPA auditing 이 createdAt 자동 채움 → 결정적 date-range/sort 검증을 위해
 * persist 직후 native UPDATE 로 createdAt override.
 */
class AdminGuestQueryRepositoryImplIT extends AbstractIntegrationTest {

    private static final long UID_FOO_1 = 9001L;
    private static final long UID_FOO_2 = 9002L;
    private static final long UID_BAR_1 = 9003L;
    private static final long UID_BAR_2 = 9004L;
    private static final long UID_BAR_3 = 9005L;

    @Autowired AdminGuestQueryRepository adminGuestQueryRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired GuestRepository guestRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager entityManager;

    private Long guestIdFoo1;
    private Long guestIdFoo2;
    private Long guestIdBar1;
    private Long guestIdBar2;
    private Long guestIdBar3;

    @BeforeEach
    void seed() {
        // FOO 1: createdAt 2025-12-01, 활동 5건.
        guestIdFoo1 = seedGuest(UID_FOO_1, "foo1@g4it.local", "FooOne",
                "Mozilla/5.0 test-foo1", LocalDateTime.of(2025, 12, 1, 0, 0));
        seedActivities(UID_FOO_1, LocalDateTime.of(2026, 4, 28, 12, 0), 5);

        // FOO 2: createdAt 2026-01-15, 활동 0건.
        guestIdFoo2 = seedGuest(UID_FOO_2, "foo2@g4it.local", "FooTwo",
                "Mozilla/5.0 test-foo2", LocalDateTime.of(2026, 1, 15, 0, 0));

        // BAR 1: createdAt 2026-03-10, 활동 0건.
        guestIdBar1 = seedGuest(UID_BAR_1, "bar1@g4it.local", "BarOne",
                "Mozilla/5.0 test-bar1", LocalDateTime.of(2026, 3, 10, 0, 0));

        // BAR 2: createdAt 2026-06-20, 활동 0건.
        guestIdBar2 = seedGuest(UID_BAR_2, "bar2@g4it.local", "BarTwo",
                "Mozilla/5.0 test-bar2", LocalDateTime.of(2026, 6, 20, 0, 0));

        // BAR 3: createdAt 2026-12-25, 활동 0건.
        guestIdBar3 = seedGuest(UID_BAR_3, "bar3@g4it.local", "BarThree",
                "Mozilla/5.0 test-bar3", LocalDateTime.of(2026, 12, 25, 0, 0));
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                            "DELETE FROM user_activity_log WHERE user_account_id IN (:uids)")
                    .setParameter("uids",
                            java.util.List.of(UID_FOO_1, UID_FOO_2, UID_BAR_1, UID_BAR_2, UID_BAR_3))
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM guest WHERE user_account_id IN (:uids)")
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

    private Long seedGuest(long uid, String email, String nickname,
                           String agent, LocalDateTime createdAt) {
        return transactionTemplate.execute(status -> {
            UserAccountData ua = UserAccountData.createForSocial(
                    new UserId(uid), email, ProviderType.GOOGLE);
            userAccountRepository.saveAndFlush(ua);
            // override JPA auditing-set createdAt — controlled value for date-range tests.
            entityManager.createNativeQuery(
                            "UPDATE user_account SET created_at = :ts WHERE user_id = :uid")
                    .setParameter("ts", createdAt)
                    .setParameter("uid", uid)
                    .executeUpdate();

            GuestData guest = GuestData.createForUserAccount(uid, agent);
            ProfileData profile = ProfileData.builder()
                    .userId(new UserId(uid))
                    .nickname(new Nickname(nickname))
                    .introduction("intro-" + uid)
                    .build();
            guest.initiateProfile(profile);
            GuestData saved = guestRepository.saveAndFlush(guest);
            return saved.getGuestId();
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
    @DisplayName("search: tier 필터 부재 — 모든 guest 반환 (page 0, size 50, default sort desc)")
    void search_no_tier_filter_returns_all_guests() {
        // email filter 로 SEED 만 격리 (V5 super-admin/외 데이터 영향 제거).
        AdminGuestListQuery query = new AdminGuestListQuery(
                "g4it.local", null, null, AdminGuestListQuery.SORT_CREATED_AT_DESC);
        Page<AdminGuestSummaryRow> page = adminGuestQueryRepository.search(query, PageRequest.of(0, 50));

        assertThat(page.getTotalElements()).isEqualTo(5L);
        assertThat(page.getContent()).hasSize(5);
        // 정렬: createdAt DESC, guestId DESC tiebreak
        // BAR3(2026-12-25) → BAR2(2026-06-20) → BAR1(2026-03-10) → FOO2(2026-01-15) → FOO1(2025-12-01)
        assertThat(page.getContent()).extracting(AdminGuestSummaryRow::guestId)
                .containsExactly(guestIdBar3, guestIdBar2, guestIdBar1, guestIdFoo2, guestIdFoo1);
    }

    @Test
    @DisplayName("search: email LIKE — case-insensitive 부분일치")
    void search_email_filter_case_insensitive() {
        AdminGuestListQuery query = new AdminGuestListQuery(
                "FOO", null, null, AdminGuestListQuery.SORT_CREATED_AT_DESC);
        Page<AdminGuestSummaryRow> page = adminGuestQueryRepository.search(query, PageRequest.of(0, 50));

        assertThat(page.getTotalElements()).isEqualTo(2L);
        assertThat(page.getContent()).extracting(AdminGuestSummaryRow::email)
                .allMatch(e -> e.toLowerCase().contains("foo"));
    }

    @Test
    @DisplayName("search: joined_from/joined_to range — inclusive 양쪽")
    void search_joined_date_range_inclusive() {
        AdminGuestListQuery query = new AdminGuestListQuery("g4it.local",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
                AdminGuestListQuery.SORT_CREATED_AT_DESC);
        Page<AdminGuestSummaryRow> page = adminGuestQueryRepository.search(query, PageRequest.of(0, 50));

        // FOO2(01-15), BAR1(03-10), BAR2(06-20) → 3건
        assertThat(page.getTotalElements()).isEqualTo(3L);
        assertThat(page.getContent()).extracting(AdminGuestSummaryRow::guestId)
                .containsExactly(guestIdBar2, guestIdBar1, guestIdFoo2);
    }

    @Test
    @DisplayName("search: sort last_activity_desc — 활동 0건은 createdAt fallback")
    void search_sort_last_activity_desc_with_fallback() {
        AdminGuestListQuery query = new AdminGuestListQuery("g4it.local", null, null,
                AdminGuestListQuery.SORT_LAST_ACTIVITY_DESC);
        Page<AdminGuestSummaryRow> page = adminGuestQueryRepository.search(query, PageRequest.of(0, 50));

        // 5명 모두 결과 포함 (활동 0건도 createdAt fallback).
        assertThat(page.getTotalElements()).isEqualTo(5L);
        // FOO1 활동 MAX(2026-04-28 12:00:04) < BAR3 createdAt(2026-12-25) → BAR3 가 먼저.
        assertThat(page.getContent().get(0).guestId()).isEqualTo(guestIdBar3);
    }

    @Test
    @DisplayName("findDetail: guest 존재 — row 반환 + agent/isProfileUpdated 포함")
    void findDetail_existing_guest_returns_row() {
        Optional<AdminGuestDetailRow> row = adminGuestQueryRepository.findDetail(guestIdFoo1);

        assertThat(row).isPresent();
        AdminGuestDetailRow r = row.get();
        assertThat(r.guestId()).isEqualTo(guestIdFoo1);
        assertThat(r.email()).isEqualTo("foo1@g4it.local");
        assertThat(r.nickname()).isEqualTo("FooOne");
        assertThat(r.agent()).isEqualTo("Mozilla/5.0 test-foo1");
        assertThat(r.isProfileUpdated()).isTrue();
    }

    @Test
    @DisplayName("findDetail: 존재하지 않는 guestId — Optional.empty")
    void findDetail_missing_guest_returns_empty() {
        assertThat(adminGuestQueryRepository.findDetail(9999999L)).isEmpty();
    }

    @Test
    @DisplayName("search: sort created_at_asc — 최오래 가입자부터")
    void search_sort_created_at_asc() {
        AdminGuestListQuery query = new AdminGuestListQuery("g4it.local", null, null,
                AdminGuestListQuery.SORT_CREATED_AT_ASC);
        Page<AdminGuestSummaryRow> page = adminGuestQueryRepository.search(query, PageRequest.of(0, 50));

        // FOO1(2025-12-01) 가 가장 오래 → 첫번째
        assertThat(page.getContent().get(0).guestId()).isEqualTo(guestIdFoo1);
    }
}
