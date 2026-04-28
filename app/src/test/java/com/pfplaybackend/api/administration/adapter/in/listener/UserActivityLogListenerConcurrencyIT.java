package com.pfplaybackend.api.administration.adapter.in.listener;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdministratorRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.entity.data.AdministratorData;
import com.pfplaybackend.api.administration.domain.enums.UserActivityEventType;
import com.pfplaybackend.api.auth.application.dto.command.AdminLoginCommand;
import com.pfplaybackend.api.auth.application.service.AdminLoginService;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.MemberRepository;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR 12a §7.2 / §8.4 — concurrency IT: 동시 admin login 2회 → user_activity_log SIGNED_IN row 2건.
 *
 * <p>두 어드민(서로 다른 user_account_id)을 SEED하여 thread A/B에서 동시 login.
 * 동일 admin x 2 thread 시나리오는 {@code MemberSignService.getMemberOrCreate}의
 * Member lazy-create race가 잠재적 flake 원인이라 본 IT는 결정적 시나리오 채택 — 2 admins
 * + 2 threads. PK auto-increment 가 row INSERT 충돌 없음을 검증.
 *
 * <p>각 {@code adminLoginService.login}은 자체 {@code @Transactional} 경계를 가지므로
 * AFTER_COMMIT listener 가 비동기로 분리 실행. Awaitility 5s 안에 두 SIGNED_IN row 도달 검증.
 *
 * <p>SEED 패턴은 {@link UserActivityLogListenerSignedInIT} 동일 (admin UA + AdministratorData).
 */
class UserActivityLogListenerConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private AdminLoginService adminLoginService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private UserActivityLogRepository userActivityLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final Long ADMIN_USER_ID_A = 9991L;
    private static final Long ADMIN_USER_ID_B = 9992L;
    private static final String ADMIN_EMAIL_A = "ual-concurrency-a-it@x";
    private static final String ADMIN_EMAIL_B = "ual-concurrency-b-it@x";
    private static final String ADMIN_PWD = "secret123";

    @BeforeEach
    void seed() {
        // 2 admins (서로 다른 user_account_id) — Member lazy-create race 회피.
        UserAccountData uaA = UserAccountData.createForLocal(
                new UserId(ADMIN_USER_ID_A), ADMIN_EMAIL_A, passwordEncoder.encode(ADMIN_PWD));
        UserAccountData uaB = UserAccountData.createForLocal(
                new UserId(ADMIN_USER_ID_B), ADMIN_EMAIL_B, passwordEncoder.encode(ADMIN_PWD));
        userAccountRepository.saveAndFlush(uaA);
        userAccountRepository.saveAndFlush(uaB);
        administratorRepository.saveAndFlush(AdministratorData.createAdmin(ADMIN_USER_ID_A, null));
        administratorRepository.saveAndFlush(AdministratorData.createAdmin(ADMIN_USER_ID_B, null));
    }

    @AfterEach
    void cleanup() {
        // listener는 @Async — Awaitility wait 통과 ⇒ async handler 완료 보장 race-free.
        // SIGNED_UP/SIGNED_IN 양쪽 user_activity_log row 모두 정리.
        // 순서: userActivityLog → member → administrator → userAccount.
        transactionTemplate.executeWithoutResult(status -> {
            userActivityLogRepository.deleteAll();
            memberRepository.deleteAll();
            administratorRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("동시 admin login 2회 → user_activity_log 2 SIGNED_IN row INSERT (PK 충돌 없음, ≤5s)")
    void concurrent_admin_login_inserts_two_SIGNED_IN_rows() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        pool.submit(() -> {
            try {
                adminLoginService.login(new AdminLoginCommand(ADMIN_EMAIL_A, ADMIN_PWD, "127.0.0.1"));
            } finally {
                latch.countDown();
            }
        });
        pool.submit(() -> {
            try {
                adminLoginService.login(new AdminLoginCommand(ADMIN_EMAIL_B, ADMIN_PWD, "127.0.0.1"));
            } finally {
                latch.countDown();
            }
        });

        boolean settled = latch.await(10, TimeUnit.SECONDS);
        assertThat(settled).as("두 login thread는 10초 내 완료되어야 함").isTrue();
        pool.shutdown();
        boolean terminated = pool.awaitTermination(5, TimeUnit.SECONDS);
        assertThat(terminated).as("executor 정상 종료 — leaked thread 없음").isTrue();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<UserActivityLogData> rows = userActivityLogRepository.findAll().stream()
                            .filter(r -> r.getEventType().equals(UserActivityEventType.SIGNED_IN.name()))
                            .toList();
                    assertThat(rows).hasSize(2);
                    assertThat(rows).extracting(UserActivityLogData::getUserAccountId)
                            .containsExactlyInAnyOrder(ADMIN_USER_ID_A, ADMIN_USER_ID_B);
                });
    }
}
