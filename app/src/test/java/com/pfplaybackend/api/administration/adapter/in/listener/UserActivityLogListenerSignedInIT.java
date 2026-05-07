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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G5 end-to-end (1/2): 어드민 로그인 → user_activity_log SIGNED_IN row 누적 검증.
 *
 * <p>실제 {@link AdminLoginService}.login 호출을 통해
 * {@link com.pfplaybackend.api.auth.domain.event.UserAccountSignedInEvent}가 publish되고,
 * {@link UserActivityLogListener#on(com.pfplaybackend.api.auth.domain.event.UserAccountSignedInEvent)}
 * 핸들러가 AFTER_COMMIT + @Async로 user_activity_log row를 INSERT하는 wiring을 검증.
 *
 * <p>SEED 패턴은 PR 11/PR 12a 어드민 IT 컨벤션 동일 — UA(LOCAL, BCrypt) + AdministratorData(active).
 * MemberSignService.getMemberOrCreate가 Member를 lazy-create하면서 MemberRegisteredEvent(SIGNED_UP)도
 * 함께 publish되므로, 본 IT는 SIGNED_IN row 만 filter해서 검증한다 (cleanup 시 양쪽 모두 제거).
 *
 * <p>@TransactionalEventListener(AFTER_COMMIT) + @Async ⇒ Awaitility로 최대 5초 poll.
 */
class UserActivityLogListenerSignedInIT extends AbstractIntegrationTest {

    @Autowired private AdminLoginService adminLoginService;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private AdministratorRepository administratorRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private UserActivityLogRepository userActivityLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final Long ADMIN_USER_ID = 9971L;
    private static final String ADMIN_EMAIL = "ual-signin-it@x";
    private static final String ADMIN_PWD = "secret123";

    @BeforeEach
    void seed() {
        // UA (LOCAL, BCrypt(secret123)) + AdministratorData (active)
        UserAccountData ua = UserAccountData.createForLocal(
                new UserId(ADMIN_USER_ID), ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PWD));
        userAccountRepository.saveAndFlush(ua);
        administratorRepository.saveAndFlush(AdministratorData.createAdmin(ADMIN_USER_ID, null));
    }

    @AfterEach
    void cleanup() {
        // listener는 @Async — Awaitility wait 통과 ⇒ async handler 완료 보장 race-free.
        // MemberSignService.getMemberOrCreate가 Member lazy-create 시 MemberRegisteredEvent(SIGNED_UP)도
        // 함께 발행하므로 SIGNED_UP/SIGNED_IN 양쪽 user_activity_log row 모두 정리된다.
        // 순서: userActivityLog → member → administrator → userAccount.
        transactionTemplate.executeWithoutResult(status -> {
            userActivityLogRepository.deleteAll();
            memberRepository.deleteAll();
            administratorRepository.deleteAll();
            userAccountRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("admin login 성공 → SIGNED_IN row (actor_type=ADMINISTRATOR, ≤5s)")
    void admin_login_inserts_SIGNED_IN_row() {
        adminLoginService.login(new AdminLoginCommand(ADMIN_EMAIL, ADMIN_PWD, "127.0.0.1"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<UserActivityLogData> rows = userActivityLogRepository.findAll().stream()
                            .filter(r -> r.getEventType().equals(UserActivityEventType.SIGNED_IN.name()))
                            .toList();
                    assertThat(rows).hasSize(1);
                    UserActivityLogData saved = rows.get(0);
                    assertThat(saved.getUserAccountId()).isEqualTo(ADMIN_USER_ID);
                    Map<String, Object> meta = saved.getMetadata().data();
                    assertThat(meta)
                            .containsEntry("actor_type", "ADMINISTRATOR")
                            .containsEntry("provider", "LOCAL");
                });
    }
}
