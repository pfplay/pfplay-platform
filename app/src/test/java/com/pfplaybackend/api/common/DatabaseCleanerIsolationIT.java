package com.pfplaybackend.api.common;

import com.pfplaybackend.api.administration.adapter.out.persistence.BugReportRepository;
import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DatabaseCleaner 결정론 격리 검증 (스펙 §5.0a).
 *
 * <p><b>비-{@code @Transactional}(커밋형)</b> IT — 각 {@code @Test} 메서드의 {@code repository.save()}
 * 는 즉시 커밋된다. 따라서 pre-transaction {@link DatabaseCleanupTestExecutionListener} 가 없으면
 * 앞 메서드가 커밋한 행이 뒤 메서드에 누적되어 보인다.
 *
 * <p><b>대칭 단언(S4 — 순서의존 제거)</b>: 세 메서드가 각자
 * "시작 시 clean(count==0) → 1건 커밋 삽입 → 1건 관측" 을 수행한다. 임의의 두 메서드가 서로의
 * 잔재를 보지 않음을 실행 순서와 무관하게 결정론적으로 증명한다 — cleaner 가 실제로 하는 일.
 *
 * <p>대상 테이블은 {@code bug_report}(V19): PRESERVE_SET 이 아니라 매 메서드 truncate 되고,
 * required-parent FK 가 없어(reporter_user_account_id/partyroom_id 는 순수 BIGINT) 부모 시드 없이
 * 커밋 저장이 가능하다.
 */
class DatabaseCleanerIsolationIT extends AbstractIntegrationTest {

    private static final long REPORTER_UID = 990001L;

    @Autowired
    BugReportRepository bugReportRepository;
    @Autowired
    DatabaseCleaner databaseCleaner;
    @Autowired
    TransactionTemplate transactionTemplate;

    private BugReportData newProbe(String desc) {
        return BugReportData.create(
                REPORTER_UID, desc, "https://pfplay.xyz/parties/lobby",
                "Mozilla/5.0", null, LocalDateTime.of(2026, 7, 9, 12, 0));
    }

    private void assertCleanThenWriteOne() {
        // cleaner 가 pre-transaction truncate → 매 메서드 시작 시 clean slate.
        assertThat(bugReportRepository.count()).isZero();
        // 커밋형 저장(클래스에 @Transactional 없음 → save 가 즉시 커밋).
        bugReportRepository.save(BugReportData.create(
                REPORTER_UID, "isolation probe", "https://pfplay.xyz/parties/lobby",
                "Mozilla/5.0", null, LocalDateTime.of(2026, 7, 9, 12, 0)));
        assertThat(bugReportRepository.count()).isEqualTo(1);
    }

    @Test
    void isolationA() {
        assertCleanThenWriteOne();
    }

    @Test
    void isolationB() {
        assertCleanThenWriteOne();
    }

    @Test
    void isolationC() {
        assertCleanThenWriteOne();
    }

    /** T5: pristine 테이블은 truncate 대상에서 제외되고 더티만 감지됨(선택적 truncate 증명). */
    @Test
    void selectDirtyTables_detectsOnlyDirtyTables() {
        // pre-test clean() 이 이미 모든 비-PRESERVE 테이블을 pristine 으로 만든 상태.
        bugReportRepository.save(newProbe("dirty probe"));   // 커밋 → bug_report 만 더티

        Set<String> dirty = databaseCleaner.selectDirtyTables();

        assertThat(dirty).contains("bug_report");
        // 이 IT 가 건드리지 않는 비-PRESERVE 테이블 = pristine → 제외되어야 함.
        assertThat(dirty).doesNotContain("system_announcement");
    }

    /** T2b: INSERT 후 롤백(행 0, engine AI 전진)도 더티로 감지되고 clean 후 AI=1 복원.
     *  8.0 통계캐시 우회(stats_expiry=0)가 없으면 stale AI=1 로 미감지되어 실패한다. */
    @Test
    void autoIncrementReset_afterInsertRollback() {
        // pre-test clean() → bug_report pristine(AI=1).
        // INSERT 후 롤백: 행은 사라지지만 InnoDB 는 소비한 AUTO_INCREMENT 를 되돌리지 않는다.
        transactionTemplate.executeWithoutResult(status -> {
            bugReportRepository.saveAndFlush(newProbe("rollback probe"));
            status.setRollbackOnly();
        });
        assertThat(bugReportRepository.count()).isZero();                        // 롤백 확인
        assertThat(databaseCleaner.selectDirtyTables()).contains("bug_report");  // AI 전진 → 더티

        databaseCleaner.clean();                                                // 더티 truncate → AI=1

        BugReportData saved = bugReportRepository.save(newProbe("after clean"));
        assertThat(saved.getBugReportId()).isEqualTo(1L);                       // AI 리셋됨
    }
}
