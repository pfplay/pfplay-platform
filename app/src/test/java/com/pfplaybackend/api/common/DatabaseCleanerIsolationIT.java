package com.pfplaybackend.api.common;

import com.pfplaybackend.api.administration.adapter.out.persistence.BugReportRepository;
import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

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
}
