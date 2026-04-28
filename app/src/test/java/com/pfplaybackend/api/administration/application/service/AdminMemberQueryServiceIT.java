package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.RecentActivityLogItem;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.administration.domain.value.JsonMetadata;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.ProfileData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import com.pfplaybackend.api.user.domain.value.Nickname;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3 IT: A-2 detail end-to-end against TestContainers MySQL.
 *
 * <p>Seeds 31 user_activity_log rows for one user, calls {@link AdminMemberQueryService#getDetail},
 * and verifies the recentActivityLog list is exactly 30 entries ordered by occurredAt DESC
 * (with log_id DESC tie-breaker per spec §11 #9).
 *
 * <p>SEED strategy mirrors PR 8 {@code AdminPartyroomQueryRepositoryImplIT}: UserAccount uses
 * a literal UserId, Member is created via {@code MemberData.createForUserAccount} + profile
 * attached via {@code initializeProfile}.
 */
class AdminMemberQueryServiceIT extends AbstractIntegrationTest {

    private static final long SEED_USER_ACCOUNT_ID = 7777L;

    @Autowired AdminMemberQueryService service;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private Long memberId;

    @BeforeEach
    void seed() {
        // 1) UserAccount SEED — local provider, fixed userId for deterministic FK.
        UserAccountData ua = UserAccountData.createForLocal(
                new UserId(SEED_USER_ACCOUNT_ID), "u@x.test", "h");
        userAccountRepository.saveAndFlush(ua);

        // 2) Member SEED — userAccountId FK + initialized profile (Nickname VO + introduction).
        MemberData member = MemberData.createForUserAccount(SEED_USER_ACCOUNT_ID);
        ProfileData profile = ProfileData.builder()
                .userId(new UserId(SEED_USER_ACCOUNT_ID))
                .nickname(new Nickname("Tester"))
                .introduction("hello")
                .build();
        member.initializeProfile(profile);
        MemberData savedMember = memberRepository.saveAndFlush(member);
        this.memberId = savedMember.getMemberId();

        // 3) user_activity_log: 31 rows over consecutive seconds — verifies the 30 limit + DESC.
        LocalDateTime base = LocalDateTime.of(2026, 4, 28, 12, 0);
        for (int i = 0; i < 31; i++) {
            userActivityLogRepository.save(UserActivityLogData.of(
                    SEED_USER_ACCOUNT_ID,
                    UserActivityEventType.PARTYROOM_ENTERED,
                    (long) (i + 1),
                    JsonMetadata.of(Map.of("seq", i)),
                    base.plusSeconds(i)));
        }
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            userActivityLogRepository.deleteAll();
            memberRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("getDetail: 31 SEED → 정확히 30 limit + occurredAt DESC 정렬")
    void getDetail_returns_30_recent_activities_in_descending_order() {
        AdminMemberDetailResponse response = service.getDetail(memberId);

        assertThat(response.memberId()).isEqualTo(memberId);
        assertThat(response.userAccount().userAccountId()).isEqualTo(SEED_USER_ACCOUNT_ID);
        assertThat(response.userAccount().email()).isEqualTo("u@x.test");
        assertThat(response.userAccount().providerType()).isEqualTo(ProviderType.LOCAL);
        assertThat(response.profile().nickname()).isEqualTo("Tester");
        assertThat(response.profile().introduction()).isEqualTo("hello");
        assertThat(response.authorityTier()).isEqualTo(AuthorityTier.AM);

        List<RecentActivityLogItem> activity = response.recentActivityLog();
        assertThat(activity).hasSize(30);

        // DESC ordering: most recent (seq=30, base+30s) is first; oldest kept (seq=1, base+1s) is last.
        assertThat(activity.get(0).metadata().data()).containsEntry("seq", 30);
        assertThat(activity.get(29).metadata().data()).containsEntry("seq", 1);

        // Every row should be PARTYROOM_ENTERED (eventType is the enum.name() string).
        assertThat(activity).allSatisfy(item ->
                assertThat(item.eventType()).isEqualTo("PARTYROOM_ENTERED"));
    }
}
