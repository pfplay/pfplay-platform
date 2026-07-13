package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminMemberWithdrawResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.application.AdminContext;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.BDDMockito.given;

/**
 * IT for {@link AdminMemberWithdrawCommandService} (PR 12b2 G3).
 *
 * <p>End-to-end: withdraw 호출 → email PII erase + lastLoginAt 미변경 + listener AFTER_COMMIT
 * 2 row(WITHDREW + ADMIN_ACTED_ON). 재호출 시 idempotent — alreadyWithdrawn=true,
 * audit row count 변화 없음.
 */
class AdminMemberWithdrawCommandServiceIT extends AbstractIntegrationTest {

    private static final long SEED_USER_ACCOUNT_ID = 9999L;
    private static final long SEED_BY_ADMIN_ID = 99L;

    @Autowired AdminMemberWithdrawCommandService service;
    @Autowired MemberRepository memberRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired UserActivityLogRepository userActivityLogRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @MockBean AdminContext adminContext;

    private Long memberId;
    private LocalDateTime seedLastLoginAt;

    @BeforeEach
    void seed() {
        UserAccountData ua = UserAccountData.createForLocal(
                new UserId(SEED_USER_ACCOUNT_ID), "wd-it@g4it.local", "h");
        ua.recordLogin();  // set lastLoginAt
        userAccountRepository.saveAndFlush(ua);
        // lastLoginAt 컬럼이 DATETIME(0)(초 정밀)이라 저장 시 초로 반올림된다. 베이스라인을
        // DB 에서 다시 읽어 반올림된 값으로 잡아야 withdraw 후 값과 정확히 비교된다.
        entityManager.clear();
        this.seedLastLoginAt = userAccountRepository.findById(new UserId(SEED_USER_ACCOUNT_ID))
                .orElseThrow().getLastLoginAt();

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
    @DisplayName("happy: withdraw + email PII erase + lastLoginAt 미변경 + listener 2 row")
    void withdraw_emits2AuditRows_andPreservesLastLoginAt() {
        AdminMemberWithdrawResponse res = service.withdraw(memberId);

        assertThat(res.alreadyWithdrawn()).isFalse();
        assertThat(res.withdrawnAt()).isNotNull();

        UserAccountData ua = userAccountRepository.findById(new UserId(SEED_USER_ACCOUNT_ID)).orElseThrow();
        assertThat(ua.isWithdrawn()).isTrue();
        assertThat(ua.getEmail()).startsWith("withdrawn-").endsWith("@withdrawn.local");
        // lastLoginAt 미변경 (roadmap §11.2.2 compliance)
        // — 베이스라인을 DB 저장값(DATETIME(0) 반올림)으로 잡았으므로 untouched 면 정확히 일치.
        //   withdraw 가 now 로 리셋했다면 초 단위로 벌어진다.
        assertThat(ua.getLastLoginAt()).isCloseTo(seedLastLoginAt, within(1, ChronoUnit.MILLIS));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<UserActivityLogData> logs = userActivityLogRepository
                    .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID);
            assertThat(logs).hasSize(2);
            // ORDER BY occurred_at DESC, log_id DESC → ADMIN_ACTED_ON 먼저(두 번째 INSERT)
            assertThat(logs.get(0).getEventType()).isEqualTo(UserActivityEventType.ADMIN_ACTED_ON.name());
            assertThat(logs.get(1).getEventType()).isEqualTo(UserActivityEventType.WITHDREW.name());
        });
    }

    @Test
    @DisplayName("idempotent 재호출: alreadyWithdrawn=true + listener row count 그대로")
    void withdraw_idempotent_noNewAuditRows() {
        service.withdraw(memberId);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(userActivityLogRepository
                        .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID))
                        .hasSize(2));

        AdminMemberWithdrawResponse res = service.withdraw(memberId);

        assertThat(res.alreadyWithdrawn()).isTrue();
        assertThat(res.withdrawnAt()).isNotNull();

        // 추가 row 없음 — short delay 후 confirm
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        assertThat(userActivityLogRepository
                .findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(SEED_USER_ACCOUNT_ID))
                .hasSize(2);
    }

    @Test
    @DisplayName("MEMBER_NOT_FOUND → 404")
    void withdraw_notFound_throws() {
        assertThatThrownBy(() -> service.withdraw(99999L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MBR-001");
    }
}
