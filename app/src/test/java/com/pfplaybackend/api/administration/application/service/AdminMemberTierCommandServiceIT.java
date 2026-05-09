package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberTierChangeResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.enums.AuthorityTier;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.MemberData;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * IT for {@link AdminMemberTierCommandService} (PR 12b2 G2).
 *
 * <p>Verifies end-to-end: tier 변경 후 {@code MemberTierChangedEvent}가 publish되고
 * {@code UserActivityLogListener.on(MemberTierChangedEvent)} (PR 12b1 G2 wired)이
 * AFTER_COMMIT + @Async로 row 2건(TIER_CHANGED + ADMIN_ACTED_ON) INSERT.
 *
 * <p>Awaitility로 최대 5초 poll. ORDER BY occurred_at DESC + log_id DESC tie-breaker로
 * ADMIN_ACTED_ON이 먼저 노출 — INSERT 순서 TIER_CHANGED → ADMIN_ACTED_ON, log_id 큰 쪽이 first.
 */
class AdminMemberTierCommandServiceIT extends AbstractIntegrationTest {

    private static final long SEED_USER_ACCOUNT_ID = 8888L;
    private static final long SEED_BY_ADMIN_ID = 99L;

    @Autowired AdminMemberTierCommandService service;
    @Autowired MemberRepository memberRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @MockBean AdminContext adminContext;

    private Long memberId;

    @BeforeEach
    void seed() {
        UserAccountData ua = UserAccountData.createForLocal(
                new UserId(SEED_USER_ACCOUNT_ID), "tier-it@g4it.local", "h");
        userAccountRepository.saveAndFlush(ua);

        MemberData member = MemberData.createForUserAccount(SEED_USER_ACCOUNT_ID);
        this.memberId = memberRepository.saveAndFlush(member).getMemberId();

        given(adminContext.currentAdministratorId()).willReturn(SEED_BY_ADMIN_ID);
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(s -> {
            userActivityLogRepository.deleteAll();
            memberRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("happy: AM → GT 변경 + AFTER_COMMIT listener 2 row(TIER_CHANGED + ADMIN_ACTED_ON)")
    void changeTier_emits2AuditRows() {
        AdminMemberTierChangeResponse res = service.changeTier(memberId,
                new AdminMemberTierChangeRequest(AuthorityTier.GT));

        assertThat(res.oldTier()).isEqualTo(AuthorityTier.AM);
        assertThat(res.newTier()).isEqualTo(AuthorityTier.GT);
        assertThat(memberRepository.findById(memberId).orElseThrow().getAuthorityTier())
                .isEqualTo(AuthorityTier.GT);

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<UserActivityLogData> logs = userActivityLogRepository
                    .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID);
            assertThat(logs).hasSize(2);
            // eventType은 VARCHAR (enum.name() 저장) — 문자열 비교
            // ORDER BY occurred_at DESC, log_id DESC → log_id 큰 쪽 먼저(ADMIN_ACTED_ON 두 번째 INSERT)
            assertThat(logs.get(0).getEventType()).isEqualTo(UserActivityEventType.ADMIN_ACTED_ON.name());
            assertThat(logs.get(1).getEventType()).isEqualTo(UserActivityEventType.TIER_CHANGED.name());
        });
    }

    @Test
    @DisplayName("TIER_UNCHANGED → 400 + listener row 0")
    void changeTier_unchanged_throws_andEmitsNoAuditRow() {
        AuthorityTier sameTier = memberRepository.findById(memberId).orElseThrow().getAuthorityTier();

        assertThatThrownBy(() -> service.changeTier(memberId,
                new AdminMemberTierChangeRequest(sameTier)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MBR-003");

        // listener 발화 없음 — short delay 후 확인
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        assertThat(userActivityLogRepository
                .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID))
                .isEmpty();
    }

    @Test
    @DisplayName("MEMBER_NOT_FOUND → 404")
    void changeTier_notFound_throws() {
        assertThatThrownBy(() -> service.changeTier(99999L,
                new AdminMemberTierChangeRequest(AuthorityTier.AM)))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MBR-001");
    }
}
