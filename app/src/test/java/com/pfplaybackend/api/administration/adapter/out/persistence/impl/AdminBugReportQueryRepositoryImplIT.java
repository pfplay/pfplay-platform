package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminBugReportQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.BugReportRepository;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.user.adapter.out.persistence.UserAccountRepository;
import com.pfplaybackend.api.user.domain.entity.data.UserAccountData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminBugReportQueryRepository IT — Testcontainers MySQL, V19 자동 적용.
 *
 * D/#8 AdminGuestQueryRepositoryImplIT 패턴 미러:
 * - TransactionTemplate.execute(...) 안에서 user_account/bug_report seed
 * - cleanup: AfterEach native DELETE
 *
 * 차이: BugReportData.create(...) 가 LocalDateTime now 파라미터 받으므로 native UPDATE 트릭 불요
 *      (D/#8 의 createdAt override 패턴은 user_account auditing 우회 용도, 본 IT 는 BugReport entity 만이라 무관).
 */
class AdminBugReportQueryRepositoryImplIT extends AbstractIntegrationTest {

    private static final long UID_R1 = 9101L;
    private static final long UID_R2 = 9102L;

    @Autowired AdminBugReportQueryRepository adminBugReportQueryRepository;
    @Autowired BugReportRepository bugReportRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManager entityManager;

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "DELETE FROM bug_report WHERE reporter_user_account_id IN (:uids)")
                    .setParameter("uids", List.of(UID_R1, UID_R2))
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM user_account WHERE user_id IN (:uids)")
                    .setParameter("uids", List.of(UID_R1, UID_R2))
                    .executeUpdate();
        });
    }

    private void seedUserAccount(long uid, String email) {
        transactionTemplate.executeWithoutResult(status -> {
            UserAccountData ua = UserAccountData.createForSocial(
                    new UserId(uid), email, ProviderType.GOOGLE);
            userAccountRepository.saveAndFlush(ua);
        });
    }

    private Long seedReport(long userId, String content, Long partyroomId, LocalDateTime createdAt) {
        return transactionTemplate.execute(status -> {
            BugReportData data = BugReportData.create(userId, content,
                    "https://pfplay.xyz/parties/" + (partyroomId == null ? "lobby" : partyroomId),
                    "Mozilla/5.0", partyroomId, createdAt);
            return bugReportRepository.saveAndFlush(data).getBugReportId();
        });
    }

    @Test
    @DisplayName("findRows — createdAt DESC 정렬 + contentPreview 80자 cap")
    void findRowsSortedAndPreview() {
        seedReport(UID_R1, "old report", 7L, LocalDateTime.of(2026, 5, 21, 9, 0));
        seedReport(UID_R1, "new report " + "x".repeat(200), 7L,
                LocalDateTime.of(2026, 5, 21, 10, 0));

        List<AdminBugReportSummaryDto> rows = adminBugReportQueryRepository.findRows(
                AdminBugReportListQuery.builder()
                        .page(0).size(10).sortBy("createdAt").direction("DESC").build());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).contentPreview()).startsWith("new report");
        assertThat(rows.get(0).contentPreview()).hasSize(80);
        assertThat(rows.get(1).contentPreview()).startsWith("old report");
    }

    @Test
    @DisplayName("findRows — content keyword + period 필터")
    void findRowsFilterByKeywordAndPeriod() {
        seedReport(UID_R1, "before in window keyword match", 7L,
                LocalDateTime.of(2026, 5, 21, 10, 0));
        seedReport(UID_R1, "not the term in window", 7L,
                LocalDateTime.of(2026, 5, 21, 11, 0));
        seedReport(UID_R1, "keyword match but outside window", 7L,
                LocalDateTime.of(2026, 6, 1, 10, 0));

        List<AdminBugReportSummaryDto> rows = adminBugReportQueryRepository.findRows(
                AdminBugReportListQuery.builder()
                        .contentKeyword("keyword")
                        .createdFrom(LocalDateTime.of(2026, 5, 21, 0, 0))
                        .createdTo(LocalDateTime.of(2026, 5, 21, 23, 59))
                        .page(0).size(10).sortBy("createdAt").direction("DESC").build());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).contentPreview()).contains("keyword match");
    }

    @Test
    @DisplayName("count + findDetail — email join 검증 + partyroom null")
    void countAndDetailWithUserJoin() {
        seedUserAccount(UID_R2, "reporter@pfplay.local");
        Long bugReportId = seedReport(UID_R2, "detailed report content", null,
                LocalDateTime.of(2026, 5, 21, 10, 0));

        long total = adminBugReportQueryRepository.count(
                AdminBugReportListQuery.builder()
                        .page(0).size(10).sortBy("createdAt").direction("DESC").build());
        assertThat(total).isGreaterThanOrEqualTo(1L);

        Optional<AdminBugReportDetailDto> detail = adminBugReportQueryRepository.findDetail(bugReportId);
        assertThat(detail).isPresent();
        assertThat(detail.get().content()).isEqualTo("detailed report content");
        assertThat(detail.get().reporterEmail()).isEqualTo("reporter@pfplay.local");
        assertThat(detail.get().partyroomId()).isNull();
        assertThat(detail.get().partyroomName()).isNull();

        Optional<AdminBugReportDetailDto> missing = adminBugReportQueryRepository.findDetail(999_999L);
        assertThat(missing).isEmpty();
    }
}
