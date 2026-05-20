# VOC — 버그 리포팅 창구 (BugReport) 구현 계획

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PFPlay Web Header 우측 🐛 버튼으로 사용자가 자유텍스트 버그 리포트를 제출, 어드민 콘솔에서 read-only 목록·상세로 조회. 3 레포 vertical slice.

**Architecture:** pfplay-platform `administration/bug_report` 모듈 = V19 + submit endpoint(`POST /api/v1/voc/bug-reports`) + admin query endpoint(`GET /api/v1/admin/voc/bug-reports[+/{id}]`) + bucket4j rate limiter. pfplay-admin = FSD 슬라이스 (entities/bug-report + features/bug-reports + widgets + 페이지) + 사이드바 "사용자 피드백" 신규 항목. pfplay-web = `features/bug-report` 슬라이스 + Header 버튼 + `useDialog().openDialog(...)` 모달 + zod 폼.

**Tech Stack:** Java 21 (`JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수), Spring Boot, JPA/QueryDSL, JUnit5, Mockito, Testcontainers MySQL/Redis, bucket4j+caffeine. pfplay-admin: React + Vite + FSD + MSW + Vitest + lucide. pfplay-web: Next.js + FSD + Vitest + react-hook-form + zod + MSW + headlessui + Tailwind.

**Spec:** `docs/superpowers/specs/2026-05-21-voc-bug-report-design.md` (브랜치 `feature/voc-bug-report`, commits `680d99c0`/`e18921e9`/`3c7f72f1`, reviewer 2-round Approved).

**브랜치 정책 (3 레포 동일 이름)**: `feature/voc-bug-report` — pfplay-platform 은 이미 spec commit 으로 존재. pfplay-admin / pfplay-web 은 Chunk 2/3 진입 시 origin/develop 에서 생성.

**머지 순서 (사용자 영역)**: backend → dev 자동배포 + smoke → admin → web → release/stg → main/prod.

---

## File Structure

### pfplay-platform (Chunk 1)

**신규 production (15)**:
- `app/src/main/resources/db/migration/V19__create_bug_report.sql` — V19 마이그레이션
- `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/BugReportData.java` — entity + `create` factory
- `app/src/main/java/com/pfplaybackend/api/administration/domain/exception/BugReportException.java` — BUG-001/002/003
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/BugReportRepository.java` — JpaRepository
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminBugReportQueryRepository.java` — query interface
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminBugReportQueryRepositoryCustom.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminBugReportQueryRepositoryImpl.java` — QueryDSL
- `app/src/main/java/com/pfplaybackend/api/administration/application/ratelimit/BugReportRateLimiter.java` — bucket4j
- `app/src/main/java/com/pfplaybackend/api/administration/application/service/BugReportCommandService.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminBugReportQueryService.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminBugReportSummaryDto.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminBugReportDetailDto.java`
- `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminBugReportListQuery.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/BugReportCommandController.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminBugReportQueryController.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/SubmitBugReportRequest.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/SubmitBugReportResponse.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminBugReportListResponse.java`
- `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminBugReportDetailResponse.java`
- `app/src/main/java/com/pfplaybackend/api/administration/config/BugReportRateLimitConfig.java` — Cache bean

**신규 test (7)**:
- `app/src/test/java/com/pfplaybackend/api/administration/application/ratelimit/BugReportRateLimiterTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/application/service/BugReportCommandServiceTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminBugReportQueryServiceTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AbstractVocCommandWebMvcTest.java` — base
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/BugReportCommandControllerTest.java`
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminBugReportQueryControllerTest.java` — D/#8 `AbstractAdminWebMvcTest` 상속
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminBugReportQueryRepositoryImplIT.java` — Testcontainers

**수정 1 (가능)**:
- `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AbstractAdminWebMvcTest.java` — `AdminBugReportQueryController` MockBean 1 줄 추가 (D/#8 정합)

### pfplay-admin (Chunk 2)

**신규 (12+)**:
- `pfplay-admin/src/entities/bug-report/{api,model}/*` — types, react-query keys
- `pfplay-admin/src/features/bug-reports/{api,model,ui}/*` — list/detail API, hooks, FilterForm, Table
- `pfplay-admin/src/widgets/bug-reports-list.tsx`
- `pfplay-admin/src/widgets/bug-reports-detail.tsx`
- `pfplay-admin/src/pages/bug-reports-page.tsx`
- `pfplay-admin/src/pages/bug-report-detail-page.tsx`
- `pfplay-admin/test/mocks/fixtures/bug-reports.ts`
- `pfplay-admin/test/mocks/handlers/bug-reports.ts`
- 다수 test 파일

**수정 3**:
- `pfplay-admin/src/app/layout.tsx` — 사이드바 메뉴 1줄 추가 (운영 관리 섹션 끝)
- `pfplay-admin/src/App.tsx` — 라우트 2줄 추가
- `pfplay-admin/test/mocks/handlers/index.ts` — bugReportHandlers 등록

### pfplay-web (Chunk 3)

**신규 (8+)**:
- `pfplay-web/src/shared/ui/icons/pf-bug.tsx` — SVG (lucide Bug 시각 미러, 의존성 없이)
- `pfplay-web/src/features/bug-report/api/submit-bug-report.ts`
- `pfplay-web/src/features/bug-report/model/bug-report-schema.ts` — zod
- `pfplay-web/src/features/bug-report/model/use-submit-bug-report.hook.ts` — react-query mutation
- `pfplay-web/src/features/bug-report/ui/bug-report-button.component.tsx` — Header 진입 아이콘
- `pfplay-web/src/features/bug-report/ui/bug-report-dialog.component.tsx` — useDialog 호출 + Form
- `pfplay-web/src/features/bug-report/ui/bug-report-form.component.tsx` — Textarea + buttons
- `pfplay-web/src/features/bug-report/index.ts`
- 다수 test 파일

**수정 4**:
- `pfplay-web/src/widgets/layouts/ui/header.component.tsx` — `<BugReportButton />` 1줄 추가 (GT 분기 바깥, `<LanguageChangeMenu />` sibling)
- `pfplay-web/src/shared/ui/icons/index.ts` — `PFBug` export
- `pfplay-web/src/shared/lib/localization/dictionaries/ko.json` — `bug_report.*` 키 추가
- `pfplay-web/src/shared/lib/localization/dictionaries/en.json` — `bug_report.*` 키 추가

빌드 명령(Windows Git Bash):
```bash
# backend
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
# admin
yarn vitest --run
# web
yarn vitest --run
```

---

## Chunk 1: pfplay-platform backend (V19 + submit + admin query + tests)

**Prerequisite**: D/#8 (PR #242 `feat(d8): 어드민 콘솔 GUEST read-only`) **already merged into develop** at `8db1e647` (2026-05-21). 본 plan 의 `AbstractAdminWebMvcTest` 수정·QueryDSL `QUserAccountData` import 패턴이 D/#8 산출물에 의존. 본 branch (`feature/voc-bug-report`) base 가 `develop` 이라 자동 포함.

### Task 1: V19 Flyway 마이그레이션 + `BugReportData` entity + `BugReportException`

**Files:**
- Create: `app/src/main/resources/db/migration/V19__create_bug_report.sql`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/BugReportData.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/exception/BugReportException.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/domain/entity/data/BugReportDataTest.java`

- [ ] **Step 1: V19 SQL 작성**

```sql
-- V19__create_bug_report.sql
-- =====================================================
-- V19: Administration context — BugReport (VOC 1차 도입)
-- Spec: docs/superpowers/specs/2026-05-21-voc-bug-report-design.md §3-1
-- 사용자가 자유텍스트 버그 제보, 어드민 콘솔에서 read-only 조회.
-- =====================================================

CREATE TABLE bug_report (
    bug_report_id              BIGINT       NOT NULL AUTO_INCREMENT,
    reporter_user_account_id   BIGINT       NOT NULL,
    content                    TEXT         NOT NULL,
    page_url                   VARCHAR(500) NULL,
    user_agent                 VARCHAR(500) NULL,
    partyroom_id               BIGINT       NULL,
    created_at                 DATETIME     NOT NULL,
    PRIMARY KEY (bug_report_id),
    INDEX idx_br_created (created_at DESC),
    INDEX idx_br_reporter (reporter_user_account_id, created_at DESC),
    INDEX idx_br_partyroom (partyroom_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 2: 실패 entity 테스트 작성**

`BugReportDataTest.java`:
```java
package com.pfplaybackend.api.administration.domain.entity.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class BugReportDataTest {

    @Test
    @DisplayName("create — 모든 필드 설정")
    void createSetsAllFields() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 21, 10, 0);
        BugReportData data = BugReportData.create(
                100L, "재생이 안 됩니다", "https://pfplay.xyz/parties/7",
                "Mozilla/5.0", 7L, now);

        assertThat(data.getReporterUserAccountId()).isEqualTo(100L);
        assertThat(data.getContent()).isEqualTo("재생이 안 됩니다");
        assertThat(data.getPageUrl()).isEqualTo("https://pfplay.xyz/parties/7");
        assertThat(data.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(data.getPartyroomId()).isEqualTo(7L);
        assertThat(data.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("create — nullable 메타(pageUrl/UA/partyroomId) null 허용")
    void createWithNullableMeta() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 21, 10, 0);
        BugReportData data = BugReportData.create(100L, "...", null, null, null, now);

        assertThat(data.getPageUrl()).isNull();
        assertThat(data.getUserAgent()).isNull();
        assertThat(data.getPartyroomId()).isNull();
    }
}
```

- [ ] **Step 3: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.domain.entity.data.BugReportDataTest"
```
Expected: FAIL — `cannot find symbol: class BugReportData`.

- [ ] **Step 4: 최소 구현**

`BugReportData.java` (V13 `PartyroomReportData` 패턴 미러, BaseEntity 미상속, `@AggregateRoot`):
```java
package com.pfplaybackend.api.administration.domain.entity.data;

import com.pfplaybackend.api.common.domain.annotation.AggregateRoot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 VOC(버그 리포팅) aggregate root.
 *
 * Lifecycle: INSERT-only (1차 도입, 답변 워크플로 out-of-scope).
 * Spec: docs/superpowers/specs/2026-05-21-voc-bug-report-design.md §3-1
 */
@AggregateRoot
@Entity
@Table(name = "bug_report", indexes = {
        @Index(name = "idx_br_created", columnList = "created_at DESC"),
        @Index(name = "idx_br_reporter", columnList = "reporter_user_account_id, created_at DESC"),
        @Index(name = "idx_br_partyroom", columnList = "partyroom_id, created_at DESC")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BugReportData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bug_report_id")
    private Long bugReportId;

    @Column(name = "reporter_user_account_id", nullable = false)
    private Long reporterUserAccountId;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "page_url", length = 500)
    private String pageUrl;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "partyroom_id")
    private Long partyroomId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static BugReportData create(Long reporterUserAccountId, String content,
                                       String pageUrl, String userAgent, Long partyroomId,
                                       LocalDateTime now) {
        BugReportData d = new BugReportData();
        d.reporterUserAccountId = reporterUserAccountId;
        d.content = content;
        d.pageUrl = pageUrl;
        d.userAgent = userAgent;
        d.partyroomId = partyroomId;
        d.createdAt = now;
        return d;
    }
}
```

`BugReportException.java`:
```java
package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

@Getter
public enum BugReportException implements DomainException {
    RATE_LIMIT_EXCEEDED("BUG-001", "잠시 후 다시 시도해주세요", ErrorType.TOO_MANY_REQUESTS),
    INVALID_LIST_QUERY("BUG-002", "유효하지 않은 목록 조회 조건입니다", ErrorType.BAD_REQUEST),
    BUG_REPORT_NOT_FOUND("BUG-003", "버그 리포트를 찾을 수 없습니다", ErrorType.NOT_FOUND);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    BugReportException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
```

- [ ] **Step 5: 통과 확인 + V19 마이그레이션 dry-run**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.domain.entity.data.BugReportDataTest"
```
Expected: 2 tests PASS.

(V19 SQL은 IT 단계 Testcontainers 가 Flyway 자동 실행 → 본 task 에선 별도 dry-run 불요.)

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/resources/db/migration/V19__create_bug_report.sql \
        app/src/main/java/com/pfplaybackend/api/administration/domain/entity/data/BugReportData.java \
        app/src/main/java/com/pfplaybackend/api/administration/domain/exception/BugReportException.java \
        app/src/test/java/com/pfplaybackend/api/administration/domain/entity/data/BugReportDataTest.java
git commit -m "feat(voc): V19 bug_report 마이그레이션 + BugReportData entity + Exception (#voc)"
```

---

### Task 2: `BugReportRateLimiter` + Cache config + Test

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/ratelimit/BugReportRateLimiter.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/config/BugReportRateLimitConfig.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/ratelimit/BugReportRateLimiterTest.java`

- [ ] **Step 1: 실패 단위 테스트 작성**

`BugReportRateLimiterTest.java`:
```java
package com.pfplaybackend.api.administration.application.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.http.TooManyRequestsException;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BugReportRateLimiterTest {

    private Cache<String, Bucket> userBuckets;
    private BugReportRateLimiter limiter;

    @BeforeEach
    void setUp() {
        userBuckets = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
        limiter = new BugReportRateLimiter(userBuckets);
    }

    @Test
    @DisplayName("첫 호출 — 통과")
    void firstCallPasses() {
        assertThatNoException().isThrownBy(() -> limiter.acquireOrThrow(1L));
    }

    @Test
    @DisplayName("1분 내 연속 2회 — 두 번째는 BUG-001 TooManyRequestsException")
    void secondCallWithinWindowThrows() {
        limiter.acquireOrThrow(1L);
        assertThatThrownBy(() -> limiter.acquireOrThrow(1L))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining(BugReportException.RATE_LIMIT_EXCEEDED.getMessage());
    }

    @Test
    @DisplayName("다른 user — 독립적으로 통과")
    void differentUserPasses() {
        limiter.acquireOrThrow(1L);
        assertThatNoException().isThrownBy(() -> limiter.acquireOrThrow(2L));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.application.ratelimit.BugReportRateLimiterTest"
```
Expected: FAIL — `cannot find symbol: class BugReportRateLimiter`.

- [ ] **Step 3: 최소 구현**

`BugReportRateLimiter.java` (AdminLoginRateLimiter 패턴 미러, ExceptionCreator throw 차이):
```java
package com.pfplaybackend.api.administration.application.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * VOC 버그리포트 rate limit — userId 별 1분 1회.
 *
 * AdminLoginRateLimiter (auth 모듈) 패턴 미러:
 * - hand-written constructor (Lombok @RequiredArgsConstructor 가 @Qualifier 를 strip)
 * - bucket4j 신 API (Bandwidth.builder().refillIntervally(...))
 * - 별도 Caffeine cache (@Qualifier("bugReportUserBuckets")) — cache key namespace 분리
 *
 * AdminLoginRateLimiter 와 차이: 본 클래스는 ExceptionCreator → TooManyRequestsException 매핑 사용
 * (inner RateLimitedException 패턴 안 씀, BUG-001 errorCode 보존 위해).
 */
@Component
public class BugReportRateLimiter {

    private static final int CAPACITY = 1;
    private static final Duration REFILL_INTERVAL = Duration.ofMinutes(1);

    private final Cache<String, Bucket> userBuckets;

    public BugReportRateLimiter(
            @Qualifier("bugReportUserBuckets") Cache<String, Bucket> userBuckets) {
        this.userBuckets = userBuckets;
    }

    public void acquireOrThrow(Long userId) {
        Bucket bucket = userBuckets.get(String.valueOf(userId), k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(CAPACITY)
                        .refillIntervally(CAPACITY, REFILL_INTERVAL)
                        .build())
                .build());
        if (!bucket.tryConsume(1)) {
            throw ExceptionCreator.create(BugReportException.RATE_LIMIT_EXCEEDED);
        }
    }
}
```

`BugReportRateLimitConfig.java` (Caffeine cache bean):
```java
package com.pfplaybackend.api.administration.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class BugReportRateLimitConfig {

    @Bean(name = "bugReportUserBuckets")
    public Cache<String, Bucket> bugReportUserBuckets() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))   // bucket idle TTL — refill 보다 길게
                .maximumSize(100_000)                       // 동시 활성 사용자 상한
                .build();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: 위 Step 2 와 동일.
Expected: 3 tests PASS.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/application/ratelimit/BugReportRateLimiter.java \
        app/src/main/java/com/pfplaybackend/api/administration/config/BugReportRateLimitConfig.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/ratelimit/BugReportRateLimiterTest.java
git commit -m "feat(voc): BugReportRateLimiter — userId 1분 1회 + Caffeine cache config (#voc)"
```

---

### Task 3: `BugReportRepository` + `BugReportCommandService` + Test

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/BugReportRepository.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/BugReportCommandService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/service/BugReportCommandServiceTest.java`

- [ ] **Step 1: repository interface**

`BugReportRepository.java`:
```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BugReportRepository extends JpaRepository<BugReportData, Long> {
}
```

- [ ] **Step 2: 실패 service 테스트 작성**

`BugReportCommandServiceTest.java`:
```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.application.ratelimit.BugReportRateLimiter;
import com.pfplaybackend.api.administration.adapter.out.persistence.BugReportRepository;
import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import com.pfplaybackend.api.common.domain.value.UserId;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import com.pfplaybackend.api.common.exception.http.TooManyRequestsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BugReportCommandServiceTest {

    @Mock BugReportRepository repository;
    @Mock BugReportRateLimiter rateLimiter;

    private BugReportCommandService service;

    private final UserId userId = new UserId(100L);
    private final Clock fixedClock = Clock.fixed(
            Instant.parse("2026-05-21T10:00:00Z"), ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        service = new BugReportCommandService(repository, rateLimiter, fixedClock);
        AuthContext ctx = mock(AuthContext.class);
        lenient().when(ctx.getUserId()).thenReturn(userId);
        ThreadLocalContext.setContext(ctx);
        when(repository.save(any(BugReportData.class))).thenAnswer(inv -> {
            BugReportData input = inv.getArgument(0);
            // simulate id assignment
            java.lang.reflect.Field idField;
            try {
                idField = BugReportData.class.getDeclaredField("bugReportId");
                idField.setAccessible(true);
                idField.set(input, 42L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return input;
        });
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContext.clearContext();
    }

    @Test
    @DisplayName("submit — happy: 모든 필드 채워 save + id 반환")
    void submitHappy() {
        Long id = service.submit("재생 안 됨", "https://pfplay.xyz/parties/7",
                "Mozilla/5.0", 7L);

        verify(rateLimiter).acquireOrThrow(100L);
        ArgumentCaptor<BugReportData> captor = ArgumentCaptor.forClass(BugReportData.class);
        verify(repository).save(captor.capture());
        BugReportData saved = captor.getValue();
        assertThat(saved.getReporterUserAccountId()).isEqualTo(100L);
        assertThat(saved.getContent()).isEqualTo("재생 안 됨");
        assertThat(saved.getPageUrl()).isEqualTo("https://pfplay.xyz/parties/7");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getPartyroomId()).isEqualTo(7L);
        assertThat(id).isEqualTo(42L);
    }

    @Test
    @DisplayName("submit — pageUrl/UA null 허용")
    void submitWithNullMeta() {
        service.submit("buggy", null, null, null);

        ArgumentCaptor<BugReportData> captor = ArgumentCaptor.forClass(BugReportData.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPageUrl()).isNull();
        assertThat(captor.getValue().getUserAgent()).isNull();
        assertThat(captor.getValue().getPartyroomId()).isNull();
    }

    @Test
    @DisplayName("submit — pageUrl 600자 → server-side truncate to 500")
    void submitTruncatesLongPageUrl() {
        String longUrl = "https://x.com/" + "a".repeat(700);  // > 500
        service.submit("buggy", longUrl, null, null);

        ArgumentCaptor<BugReportData> captor = ArgumentCaptor.forClass(BugReportData.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPageUrl()).hasSize(500);
        assertThat(captor.getValue().getPageUrl()).startsWith("https://x.com/");
    }

    @Test
    @DisplayName("submit — rate-limit throw → save 0회")
    void submitRateLimitThrows() {
        doThrow(ExceptionCreator.create(BugReportException.RATE_LIMIT_EXCEEDED))
                .when(rateLimiter).acquireOrThrow(100L);

        assertThatThrownBy(() -> service.submit("buggy", null, null, null))
                .isInstanceOf(TooManyRequestsException.class);
        verify(repository, never()).save(any());
    }
}
```

- [ ] **Step 3: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.application.service.BugReportCommandServiceTest"
```
Expected: FAIL — `cannot find symbol: class BugReportCommandService`.

- [ ] **Step 4: 최소 구현**

`BugReportCommandService.java`:
```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.BugReportRepository;
import com.pfplaybackend.api.administration.application.ratelimit.BugReportRateLimiter;
import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import com.pfplaybackend.api.common.ThreadLocalContext;
import com.pfplaybackend.api.common.adapter.in.web.RequestIdInterceptor;
import com.pfplaybackend.api.common.aspect.context.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BugReportCommandService {

    private static final int META_MAX_LENGTH = 500;

    private final BugReportRepository repository;
    private final BugReportRateLimiter rateLimiter;
    private final Clock clock;

    @Transactional
    public Long submit(String content, String pageUrl, String userAgent, Long partyroomId) {
        AuthContext authContext = ThreadLocalContext.getAuthContext();
        Long userId = authContext.getUserId().getUid();
        log.info("[bugReport.submit] ENTER requestId={} reporterUserId={} partyroomId={}",
                RequestIdInterceptor.current(), userId, partyroomId);

        rateLimiter.acquireOrThrow(userId);

        BugReportData data = BugReportData.create(
                userId,
                content,
                truncate(pageUrl, META_MAX_LENGTH),
                truncate(userAgent, META_MAX_LENGTH),
                partyroomId,
                LocalDateTime.now(clock));
        BugReportData saved = repository.save(data);

        log.info("[bugReport.submit] OK requestId={} reporterUserId={} bugReportId={}",
                RequestIdInterceptor.current(), userId, saved.getBugReportId());
        return saved.getBugReportId();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
```

> Clock bean: 프로젝트에 `ClockConfig.kst()` 가 이미 존재 (`[[project_jvm_tz_kst_policy]]`). 본 service 의 `@RequiredArgsConstructor` 가 자동 주입.

- [ ] **Step 5: 통과 확인**

Run: 위 Step 3 와 동일.
Expected: 4 tests PASS.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/BugReportRepository.java \
        app/src/main/java/com/pfplaybackend/api/administration/application/service/BugReportCommandService.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/BugReportCommandServiceTest.java
git commit -m "feat(voc): BugReportCommandService.submit — Clock 주입·truncate·rate-limit·INFO 로그 (#voc)"
```

---

### Task 4: `BugReportCommandController` + `AbstractVocCommandWebMvcTest` + DTO + Test

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/SubmitBugReportRequest.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/SubmitBugReportResponse.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/BugReportCommandController.java`
- Test base: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AbstractVocCommandWebMvcTest.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/BugReportCommandControllerTest.java`

- [ ] **Step 1: DTO 작성**

`SubmitBugReportRequest.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class SubmitBugReportRequest {
    @NotBlank
    @Size(min = 5, max = 2000)
    private String content;

    @Positive
    private Long partyroomId;   // nullable; if present must be > 0
}
```

`SubmitBugReportResponse.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

public record SubmitBugReportResponse(Long bugReportId) {}
```

- [ ] **Step 2: 실패 controller WebMvc 테스트 작성**

`AbstractVocCommandWebMvcTest.java` (기존 `AbstractPartyCommandWebMvcTest` 패턴 미러):
```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.application.service.BugReportCommandService;
import com.pfplaybackend.api.common.config.security.jwt.AdminCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.JwtService;
import com.pfplaybackend.api.common.config.security.jwt.SharedSessionCookieWriter;
import com.pfplaybackend.api.common.config.security.jwt.properties.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BugReportCommandController.class)
@Import(AbstractVocCommandWebMvcTest.SharedMethodSecurityConfig.class)
abstract class AbstractVocCommandWebMvcTest {

    @Configuration
    @EnableMethodSecurity
    static class SharedMethodSecurityConfig {}

    @Autowired protected MockMvc mockMvc;
    @MockBean protected BugReportCommandService bugReportCommandService;
    @MockBean protected JwtDecoder jwtDecoder;
    @MockBean protected JwtService jwtService;
    @MockBean protected JwtProperties jwtProperties;
    @MockBean protected SharedSessionCookieWriter sharedSessionCookieWriter;
    @MockBean protected AdminCookieWriter adminCookieWriter;
}
```

`BugReportCommandControllerTest.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BugReportCommandControllerTest extends AbstractVocCommandWebMvcTest {

    @Test
    @DisplayName("submit — 201 Created + bugReportId 반환")
    void submitReturns201() throws Exception {
        when(bugReportCommandService.submit(any(), any(), any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .header("Referer", "https://pfplay.xyz/parties/7")
                        .header("User-Agent", "Mozilla/5.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"재생이 안 됩니다\",\"partyroomId\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bugReportId").value(42));
    }

    @Test
    @DisplayName("submit — content 너무 짧으면 400")
    void submitShortContentReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"abcd\"}"))   // 4자 < 5
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("submit — content blank 400")
    void submitBlankContentReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("submit — partyroomId 0/negative 400")
    void submitInvalidPartyroomIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"long enough content\",\"partyroomId\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("submit — 인증 없으면 401")
    void submitUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"valid content\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("submit — rate-limit 시 429 + BUG-001")
    void submitRateLimitReturns429() throws Exception {
        doThrow(ExceptionCreator.create(BugReportException.RATE_LIMIT_EXCEEDED))
                .when(bugReportCommandService).submit(any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_MEMBER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"valid content\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("BUG-001"));
    }
}
```

- [ ] **Step 3: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.adapter.in.web.BugReportCommandControllerTest"
```
Expected: 6 cases 모두 404 또는 컴파일 에러 (Controller 미존재).

- [ ] **Step 4: Controller 구현**

`BugReportCommandController.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.SubmitBugReportRequest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.SubmitBugReportResponse;
import com.pfplaybackend.api.administration.application.service.BugReportCommandService;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "VOC — Bug Report API")
@RequestMapping("/api/v1/voc/bug-reports")
@RestController
@RequiredArgsConstructor
public class BugReportCommandController {

    private final BugReportCommandService bugReportCommandService;

    @Operation(summary = "버그 리포트 제출",
            description = "사용자가 겪은 버그를 자유텍스트로 제출합니다. 분당 1회 제한. 멤버·게스트 모두 허용.")
    @ApiResponse(responseCode = "201", description = "제출 성공")
    @SecurityRequirement(name = "cookieAuth")
    @ApiErrorCodes({BugReportException.class})
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiCommonResponse<SubmitBugReportResponse>> submit(
            @Valid @RequestBody SubmitBugReportRequest request,
            @RequestHeader(value = "Referer", required = false) String referer,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        Long id = bugReportCommandService.submit(
                request.getContent(),
                referer,
                userAgent,
                request.getPartyroomId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiCommonResponse.success(new SubmitBugReportResponse(id)));
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: 위 Step 3 와 동일.
Expected: 6 tests PASS.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/BugReportCommandController.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/SubmitBugReportRequest.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/SubmitBugReportResponse.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AbstractVocCommandWebMvcTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/BugReportCommandControllerTest.java
git commit -m "feat(voc): POST /api/v1/voc/bug-reports — submit 201/400/401/429 (#voc)"
```

---

### Task 5: Admin Query Repository (interface + Impl QueryDSL) + Test IT

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminBugReportListQuery.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminBugReportSummaryDto.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminBugReportDetailDto.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminBugReportQueryRepository.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminBugReportQueryRepositoryCustom.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminBugReportQueryRepositoryImpl.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminBugReportQueryRepositoryImplIT.java`

- [ ] **Step 1: 검토 — D/#8 `AdminGuestQueryRepositoryImpl` 패턴 확인**

작업 시작 전 `AdminGuestQueryRepositoryImpl.java` 한번 열어 QueryDSL JPQL 구조·count separate query·tuple→DTO mapping 패턴 파악. 본 plan 의 IT seed/assertion 도 그 patterns 미러.

- [ ] **Step 2: DTO 작성**

`AdminBugReportListQuery.java`:
```java
package com.pfplaybackend.api.administration.application.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AdminBugReportListQuery {
    private final LocalDateTime createdFrom;
    private final LocalDateTime createdTo;
    private final String contentKeyword;
    private final int page;
    private final int size;
    private final String sortBy;        // 현재 "createdAt" 만 허용
    private final String direction;     // "ASC"|"DESC"
}
```

`AdminBugReportSummaryDto.java`:
```java
package com.pfplaybackend.api.administration.application.dto;

import java.time.LocalDateTime;

public record AdminBugReportSummaryDto(
        Long bugReportId,
        Long reporterUserAccountId,
        String reporterEmail,
        String reporterNickname,
        String contentPreview,    // 80자 cap
        Long partyroomId,
        LocalDateTime createdAt
) {}
```

`AdminBugReportDetailDto.java`:
```java
package com.pfplaybackend.api.administration.application.dto;

import java.time.LocalDateTime;

public record AdminBugReportDetailDto(
        Long bugReportId,
        Long reporterUserAccountId,
        String reporterEmail,
        String reporterNickname,
        String content,
        String pageUrl,
        String userAgent,
        Long partyroomId,
        String partyroomName,
        LocalDateTime createdAt
) {}
```

- [ ] **Step 3: Repository interface (composite)**

`AdminBugReportQueryRepository.java` (D/#8 정합 — 동일 entity 의 read-only 별 path):
```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

public interface AdminBugReportQueryRepository extends AdminBugReportQueryRepositoryCustom {
}
```

`AdminBugReportQueryRepositoryCustom.java`:
```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;

import java.util.List;
import java.util.Optional;

public interface AdminBugReportQueryRepositoryCustom {
    List<AdminBugReportSummaryDto> findRows(AdminBugReportListQuery query);
    long count(AdminBugReportListQuery query);
    Optional<AdminBugReportDetailDto> findDetail(Long bugReportId);
}
```

- [ ] **Step 4: 실패 IT 작성**

`AdminBugReportQueryRepositoryImplIT.java` (D/#8 `AdminGuestQueryRepositoryImplIT` 패턴 미러, V19 schema + Flyway 자동, **createdAt override 트릭 불요** — BugReportData.create 가 LocalDateTime 받음):

```java
package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminBugReportQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.BugReportRepository;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.administration.domain.entity.data.BugReportData;
import com.pfplaybackend.api.common.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class AdminBugReportQueryRepositoryImplIT extends AbstractIntegrationTest {

    @Autowired private BugReportRepository repository;
    @Autowired private AdminBugReportQueryRepository queryRepository;
    @Autowired private EntityManager em;

    // user_account 직접 seed — 외래 데이터 (D/#8 IT pattern: 실제 user_account row 가 있어야 join 결과 join 됨)
    // user_account 시드 헬퍼는 D/#8 IT 의 동일 헬퍼 시그니처 모방 — 본 task 실행 시 base 파일 확인 후 정확히 사용

    private BugReportData seedReport(Long userId, String content, Long partyroomId, LocalDateTime createdAt) {
        BugReportData data = BugReportData.create(userId, content,
                "https://pfplay.xyz/parties/" + (partyroomId == null ? "" : partyroomId),
                "Mozilla/5.0", partyroomId, createdAt);
        return repository.save(data);
    }

    @Test
    @DisplayName("findRows — createdAt DESC 정렬, paging")
    void findRowsSortedDesc() {
        seedReport(100L, "old", 7L, LocalDateTime.of(2026, 5, 21, 9, 0));
        seedReport(100L, "new", 7L, LocalDateTime.of(2026, 5, 21, 10, 0));

        em.flush(); em.clear();

        List<AdminBugReportSummaryDto> rows = queryRepository.findRows(
                AdminBugReportListQuery.builder()
                        .page(0).size(10)
                        .sortBy("createdAt").direction("DESC")
                        .build());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).contentPreview()).startsWith("new");
        assertThat(rows.get(1).contentPreview()).startsWith("old");
    }

    @Test
    @DisplayName("findRows — contentKeyword 필터")
    void findRowsContentKeyword() {
        seedReport(100L, "재생 안 됨", 7L, LocalDateTime.of(2026, 5, 21, 10, 0));
        seedReport(100L, "다른 버그", 7L, LocalDateTime.of(2026, 5, 21, 11, 0));

        em.flush(); em.clear();

        List<AdminBugReportSummaryDto> rows = queryRepository.findRows(
                AdminBugReportListQuery.builder()
                        .contentKeyword("재생")
                        .page(0).size(10)
                        .sortBy("createdAt").direction("DESC")
                        .build());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).contentPreview()).contains("재생");
    }

    @Test
    @DisplayName("findRows — 기간(createdFrom/To) 필터")
    void findRowsPeriodFilter() {
        seedReport(100L, "before", null, LocalDateTime.of(2026, 5, 20, 10, 0));
        seedReport(100L, "in",     null, LocalDateTime.of(2026, 5, 21, 10, 0));
        seedReport(100L, "after",  null, LocalDateTime.of(2026, 5, 22, 10, 0));

        em.flush(); em.clear();

        List<AdminBugReportSummaryDto> rows = queryRepository.findRows(
                AdminBugReportListQuery.builder()
                        .createdFrom(LocalDateTime.of(2026, 5, 21, 0, 0))
                        .createdTo(LocalDateTime.of(2026, 5, 21, 23, 59))
                        .page(0).size(10)
                        .sortBy("createdAt").direction("DESC")
                        .build());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).contentPreview()).startsWith("in");
    }

    @Test
    @DisplayName("findRows — contentPreview 80자 cap")
    void findRowsPreviewCap() {
        seedReport(100L, "a".repeat(120), null, LocalDateTime.of(2026, 5, 21, 10, 0));

        em.flush(); em.clear();

        List<AdminBugReportSummaryDto> rows = queryRepository.findRows(
                AdminBugReportListQuery.builder()
                        .page(0).size(10).sortBy("createdAt").direction("DESC").build());

        assertThat(rows.get(0).contentPreview()).hasSize(80);
    }

    @Test
    @DisplayName("count + findDetail — 정상")
    void countAndDetail() {
        BugReportData saved = seedReport(100L, "ABCD".repeat(20), 7L,
                LocalDateTime.of(2026, 5, 21, 10, 0));

        em.flush(); em.clear();

        long total = queryRepository.count(
                AdminBugReportListQuery.builder()
                        .page(0).size(10).sortBy("createdAt").direction("DESC").build());
        assertThat(total).isEqualTo(1L);

        Optional<AdminBugReportDetailDto> detail = queryRepository.findDetail(saved.getBugReportId());
        assertThat(detail).isPresent();
        assertThat(detail.get().content()).isEqualTo("ABCD".repeat(20));
        assertThat(detail.get().partyroomId()).isEqualTo(7L);

        Optional<AdminBugReportDetailDto> missing = queryRepository.findDetail(999_999L);
        assertThat(missing).isEmpty();
    }
}
```

> **시드 helper**: D/#8 `AdminGuestQueryRepositoryImplIT` 에 `seedUserAccount`/`seedPartyroom` 공유 helper **존재하지 않음** (plan reviewer round-1 검증). 본 IT 내부에 private helper 직접 작성 — `EntityManager.persist(UserAccountData.builder()...build())` 또는 `entityManager.createNativeQuery("INSERT INTO user_account ...").executeUpdate()` (D/#8 IT 의 내부 패턴 확인 후 일관 적용). reporterEmail 검증 case (count + detail) 에서만 필수, sort/period/keyword filter case 는 user 시드 없이도 통과.

- [ ] **Step 5: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:integrationTest --tests "com.pfplaybackend.api.administration.adapter.out.persistence.impl.AdminBugReportQueryRepositoryImplIT"
```
Expected: 컴파일 에러 — `AdminBugReportQueryRepositoryImpl` 미존재.

- [ ] **Step 6: Impl QueryDSL 구현**

`AdminBugReportQueryRepositoryImpl.java`:
```java
package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminBugReportQueryRepositoryCustom;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.administration.domain.entity.data.QBugReportData;
// ⚠️ user 모듈 (iam 아님 — AdminMember/AdminPartyroom QueryRepositoryImpl 정합):
import com.pfplaybackend.api.user.domain.entity.data.QUserAccountData;
import com.pfplaybackend.api.party.domain.entity.data.QPartyroomData;
// nickname source: user_account 에 nickname 컬럼 없음 → profile.bio.nickname join 필요
// (AdminPartyroomQueryRepositoryImpl.java:80 패턴 미러). 본 plan 단순 버전은
// reporterNickname null 허용 + 후속 task 에서 profile join 추가 가능 (YAGNI 결정 — 1차 도입은 email 만):
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AdminBugReportQueryRepositoryImpl implements AdminBugReportQueryRepositoryCustom {

    private static final int PREVIEW_LENGTH = 80;

    private final JPAQueryFactory queryFactory;

    @Override
    public List<AdminBugReportSummaryDto> findRows(AdminBugReportListQuery query) {
        QBugReportData br = QBugReportData.bugReportData;
        QUserAccountData ua = QUserAccountData.userAccountData;

        StringExpression preview = br.content.substring(0, PREVIEW_LENGTH);

        return queryFactory
                .select(Projections.constructor(AdminBugReportSummaryDto.class,
                        br.bugReportId,
                        br.reporterUserAccountId,
                        ua.email,
                        Expressions.nullExpression(String.class),   // reporterNickname — 1차 도입은 null (profile join 후속)
                        preview,
                        br.partyroomId,
                        br.createdAt))
                .from(br)
                .leftJoin(ua).on(ua.id.eq(br.reporterUserAccountId))
                .where(buildPredicate(br, query))
                .orderBy(br.createdAt.desc())  // sortBy=createdAt만 현재 허용
                .offset((long) query.getPage() * query.getSize())
                .limit(query.getSize())
                .fetch();
    }

    @Override
    public long count(AdminBugReportListQuery query) {
        QBugReportData br = QBugReportData.bugReportData;
        Long total = queryFactory.select(br.count())
                .from(br)
                .where(buildPredicate(br, query))
                .fetchOne();
        return total == null ? 0L : total;
    }

    @Override
    public Optional<AdminBugReportDetailDto> findDetail(Long bugReportId) {
        QBugReportData br = QBugReportData.bugReportData;
        QUserAccountData ua = QUserAccountData.userAccountData;
        QPartyroomData p = QPartyroomData.partyroomData;

        AdminBugReportDetailDto result = queryFactory
                .select(Projections.constructor(AdminBugReportDetailDto.class,
                        br.bugReportId,
                        br.reporterUserAccountId,
                        ua.email,
                        Expressions.nullExpression(String.class),   // reporterNickname — 1차 도입은 null (profile join 후속)
                        br.content,
                        br.pageUrl,
                        br.userAgent,
                        br.partyroomId,
                        p.name,
                        br.createdAt))
                .from(br)
                .leftJoin(ua).on(ua.id.eq(br.reporterUserAccountId))
                .leftJoin(p).on(p.id.eq(br.partyroomId))
                .where(br.bugReportId.eq(bugReportId))
                .fetchOne();
        return Optional.ofNullable(result);
    }

    private BooleanBuilder buildPredicate(QBugReportData br, AdminBugReportListQuery query) {
        BooleanBuilder builder = new BooleanBuilder();
        if (query.getCreatedFrom() != null) builder.and(br.createdAt.goe(query.getCreatedFrom()));
        if (query.getCreatedTo() != null)   builder.and(br.createdAt.loe(query.getCreatedTo()));
        if (query.getContentKeyword() != null && !query.getContentKeyword().isBlank()) {
            builder.and(br.content.containsIgnoreCase(query.getContentKeyword()));
        }
        return builder;
    }
}
```

> QueryDSL Q-class 경로 (verified against `AdminPartyroomQueryRepositoryImpl.java:7,12,67`):
> - `QUserAccountData` = `com.pfplaybackend.api.user.domain.entity.data.QUserAccountData`
> - `QPartyroomData` = `com.pfplaybackend.api.party.domain.entity.data.QPartyroomData`
>
> **reporterNickname**: `user_account` 자체에 nickname 컬럼 없음. 다른 admin query 들은 `profile.bio.nickname` join 사용 (AdminPartyroomQueryRepositoryImpl.java:80). **본 plan 1차 도입은 null 처리** (Expressions.nullExpression) — 어드민 진단에 email 이 더 본질적이고, profile entity join 은 plan 분량 ↑ + ProfileData 모듈 cross 도메인 의존성 추가. 후속 PR 에서 profile join 보강.

- [ ] **Step 7: 통과 확인**

Run: 위 Step 5 와 동일.
Expected: 5 IT cases PASS. (V19 마이그레이션은 Testcontainers 가 Flyway 자동 실행.)

- [ ] **Step 8: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/application/dto/AdminBugReport*.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminBugReportQueryRepository*.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminBugReportQueryRepositoryImpl.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminBugReportQueryRepositoryImplIT.java
git commit -m "feat(voc): AdminBugReportQueryRepository(+Impl) QueryDSL + IT 5 (#voc)"
```

---

### Task 6: `AdminBugReportQueryService` + Test

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminBugReportQueryService.java`
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminBugReportQueryServiceTest.java`

- [ ] **Step 1: 실패 service 테스트 작성**

`AdminBugReportQueryServiceTest.java`:
```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.out.persistence.AdminBugReportQueryRepository;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.common.exception.http.BadRequestException;
import com.pfplaybackend.api.common.exception.http.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBugReportQueryServiceTest {

    @Mock AdminBugReportQueryRepository repository;
    @InjectMocks AdminBugReportQueryService service;

    @Test
    @DisplayName("getList — 정상 paging + count")
    void getListHappy() {
        AdminBugReportSummaryDto row = new AdminBugReportSummaryDto(
                1L, 100L, "user@x.com", "nick", "preview", 7L,
                LocalDateTime.of(2026, 5, 21, 10, 0));
        when(repository.findRows(any())).thenReturn(List.of(row));
        when(repository.count(any())).thenReturn(1L);

        var result = service.getList(AdminBugReportListQuery.builder()
                .page(0).size(20).sortBy("createdAt").direction("DESC").build());

        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).bugReportId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getList — sortBy 미지원이면 BUG-002")
    void getListInvalidSortByThrows() {
        assertThatThrownBy(() -> service.getList(AdminBugReportListQuery.builder()
                        .page(0).size(20).sortBy("badField").direction("DESC").build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("getList — direction 미지원이면 BUG-002")
    void getListInvalidDirectionThrows() {
        assertThatThrownBy(() -> service.getList(AdminBugReportListQuery.builder()
                        .page(0).size(20).sortBy("createdAt").direction("RANDOM").build()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("getDetail — 정상")
    void getDetailHappy() {
        AdminBugReportDetailDto detail = new AdminBugReportDetailDto(
                1L, 100L, "user@x.com", "nick", "content body",
                "https://pfplay.xyz/parties/7", "Mozilla/5.0", 7L, "테스트 룸",
                LocalDateTime.of(2026, 5, 21, 10, 0));
        when(repository.findDetail(1L)).thenReturn(Optional.of(detail));

        AdminBugReportDetailDto result = service.getDetail(1L);

        assertThat(result.content()).isEqualTo("content body");
    }

    @Test
    @DisplayName("getDetail — 없으면 BUG-003 NotFoundException")
    void getDetailNotFoundThrows() {
        when(repository.findDetail(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(999L))
                .isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.application.service.AdminBugReportQueryServiceTest"
```
Expected: 컴파일 에러.

- [ ] **Step 3: Service 구현**

`AdminBugReportQueryService.java`:
```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminBugReportListResponse;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminBugReportQueryRepository;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminBugReportQueryService {

    private static final Set<String> ALLOWED_SORT_BY = Set.of("createdAt");
    private static final Set<String> ALLOWED_DIRECTION = Set.of("ASC", "DESC");

    private final AdminBugReportQueryRepository repository;

    public AdminBugReportListResponse getList(AdminBugReportListQuery query) {
        if (!ALLOWED_SORT_BY.contains(query.getSortBy())
                || !ALLOWED_DIRECTION.contains(query.getDirection())
                || query.getPage() < 0
                || query.getSize() <= 0
                || query.getSize() > 100) {
            throw ExceptionCreator.create(BugReportException.INVALID_LIST_QUERY);
        }
        List<AdminBugReportSummaryDto> rows = repository.findRows(query);
        long total = repository.count(query);
        return new AdminBugReportListResponse(
                total,
                (long) Math.ceil((double) total / query.getSize()),
                query.getPage(),
                query.getSize(),
                rows);
    }

    public AdminBugReportDetailDto getDetail(Long bugReportId) {
        return repository.findDetail(bugReportId)
                .orElseThrow(() -> ExceptionCreator.create(BugReportException.BUG_REPORT_NOT_FOUND));
    }
}
```

`AdminBugReportListResponse.java` (controller layer response):
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;

import java.util.List;

public record AdminBugReportListResponse(
        long totalElements,
        long totalPages,
        int page,
        int size,
        List<AdminBugReportSummaryDto> items
) {}
```

`AdminBugReportDetailResponse.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;

public record AdminBugReportDetailResponse(AdminBugReportDetailDto detail) {}
```

- [ ] **Step 4: 통과 확인**

Run: 위 Step 2 와 동일.
Expected: 5 cases PASS.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminBugReportQueryService.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminBugReportListResponse.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminBugReportDetailResponse.java \
        app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminBugReportQueryServiceTest.java
git commit -m "feat(voc): AdminBugReportQueryService — sortBy/direction validate + NOT_FOUND (#voc)"
```

---

### Task 7: `AdminBugReportQueryController` + Test

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminBugReportQueryController.java`
- Modify: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AbstractAdminWebMvcTest.java` (D/#8 기존 base — `AdminBugReportQueryController.class` + `@MockBean AdminBugReportQueryService` 1줄씩 추가)
- Test: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminBugReportQueryControllerTest.java`

- [ ] **Step 1: 실패 WebMvc 테스트 작성**

`AdminBugReportQueryControllerTest.java` (D/#8 `AdminGuestQueryControllerTest` 패턴 미러):
```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminBugReportListResponse;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportDetailDto;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportSummaryDto;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminBugReportQueryControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @DisplayName("getList — 200 OK + items")
    void getListReturns200() throws Exception {
        AdminBugReportListResponse response = new AdminBugReportListResponse(
                1L, 1L, 0, 20,
                List.of(new AdminBugReportSummaryDto(
                        1L, 100L, "user@x.com", "nick", "preview", 7L,
                        LocalDateTime.of(2026, 5, 21, 10, 0))));
        when(adminBugReportQueryService.getList(any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/voc/bug-reports")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))  // adminAuth.isAdmin() mock
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].bugReportId").value(1));
    }

    @Test
    @DisplayName("getList — 잘못된 sortBy 400 BUG-002")
    void getListInvalidSortBy400() throws Exception {
        when(adminBugReportQueryService.getList(any()))
                .thenThrow(ExceptionCreator.create(BugReportException.INVALID_LIST_QUERY));

        mockMvc.perform(get("/api/v1/admin/voc/bug-reports")
                        .param("sortBy", "bad")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BUG-002"));
    }

    @Test
    @DisplayName("getList — admin 미인증 401")
    void getListUnauthorized401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/voc/bug-reports").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("getDetail — 200 OK")
    void getDetailReturns200() throws Exception {
        when(adminBugReportQueryService.getDetail(1L)).thenReturn(
                new AdminBugReportDetailDto(1L, 100L, "user@x.com", "nick",
                        "content", "https://pfplay.xyz/parties/7", "Mozilla/5.0",
                        7L, "테스트 룸", LocalDateTime.of(2026, 5, 21, 10, 0)));

        mockMvc.perform(get("/api/v1/admin/voc/bug-reports/1")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.content").value("content"))
                .andExpect(jsonPath("$.data.detail.partyroomName").value("테스트 룸"));
    }

    @Test
    @DisplayName("getDetail — 미존재 404 BUG-003")
    void getDetailNotFound404() throws Exception {
        when(adminBugReportQueryService.getDetail(999L))
                .thenThrow(ExceptionCreator.create(BugReportException.BUG_REPORT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/admin/voc/bug-reports/999")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("BUG-003"));
    }
}
```

> ⚠️ **adminAuth.isAdmin() mock**: D/#8 `AbstractAdminWebMvcTest` 가 이미 `@Bean adminAuth` 등 정합 셋업을 가지고 있어 `ROLE_ADMIN` jwt 만으로 `@adminAuth.isAdmin()` SpEL 이 통과해야 함. 안 통과 시 base test 의 mock 전략 정확히 확인 (실행 시점에 base 파일 참조).

- [ ] **Step 2: AbstractAdminWebMvcTest 에 controller/MockBean 추가 (1줄씩)**

D/#8 base `AbstractAdminWebMvcTest.java` 의 `@WebMvcTest({...})` 배열에 `AdminBugReportQueryController.class` 추가. `@MockBean` 라인 `protected AdminBugReportQueryService adminBugReportQueryService;` 추가.

- [ ] **Step 3: 실패 확인**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "com.pfplaybackend.api.administration.adapter.in.web.AdminBugReportQueryControllerTest"
```
Expected: 404 또는 컴파일 에러.

- [ ] **Step 4: Controller 구현**

`AdminBugReportQueryController.java`:
```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminBugReportDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminBugReportListResponse;
import com.pfplaybackend.api.administration.application.dto.AdminBugReportListQuery;
import com.pfplaybackend.api.administration.application.service.AdminBugReportQueryService;
import com.pfplaybackend.api.administration.domain.exception.BugReportException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.config.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "Admin VOC — Bug Report Query API")
@RequestMapping("/api/v1/admin/voc/bug-reports")
@RestController
@RequiredArgsConstructor
public class AdminBugReportQueryController {

    private final AdminBugReportQueryService adminBugReportQueryService;

    @Operation(summary = "버그 리포트 목록 조회", description = "어드민 read-only. 기간/키워드 필터, createdAt DESC 정렬.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @SecurityRequirement(name = "adminAuth")
    @ApiErrorCodes({BugReportException.class})
    @GetMapping
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminBugReportListResponse>> getList(
            @Parameter @RequestParam(defaultValue = "0") int page,
            @Parameter @RequestParam(defaultValue = "20") int size,
            @Parameter @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter @RequestParam(defaultValue = "DESC") String direction,
            @Parameter @RequestParam(required = false) String contentKeyword,
            @Parameter @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @Parameter @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo) {
        AdminBugReportListQuery query = AdminBugReportListQuery.builder()
                .page(page).size(size).sortBy(sortBy).direction(direction)
                .contentKeyword(contentKeyword)
                .createdFrom(createdFrom).createdTo(createdTo)
                .build();
        return ResponseEntity.ok(ApiCommonResponse.success(adminBugReportQueryService.getList(query)));
    }

    @Operation(summary = "버그 리포트 상세 조회", description = "어드민 read-only.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @SecurityRequirement(name = "adminAuth")
    @ApiErrorCodes({BugReportException.class})
    @GetMapping("/{bugReportId}")
    @PreAuthorize("@adminAuth.isAdmin()")
    public ResponseEntity<ApiCommonResponse<AdminBugReportDetailResponse>> getDetail(
            @Parameter @PathVariable Long bugReportId) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                new AdminBugReportDetailResponse(adminBugReportQueryService.getDetail(bugReportId))));
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: 위 Step 3 와 동일.
Expected: 5 cases PASS.

- [ ] **Step 6: 전체 backend 회귀 + 커밋**

Run:
```bash
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```
Expected: BUILD SUCCESSFUL (전체).

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminBugReportQueryController.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AbstractAdminWebMvcTest.java \
        app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminBugReportQueryControllerTest.java
git commit -m "feat(voc): GET /api/v1/admin/voc/bug-reports[+/{id}] — list+detail 5 WebMvc (#voc)"
```

---

### Chunk 1 종료 — pfplay-platform PR

- [ ] **Step 1: branch push + PR 생성**

```bash
git push -u origin feature/voc-bug-report
gh pr create --base develop --title "feat(voc): 버그 리포팅 창구 backend — V19 + submit + admin query" --body "$(cat <<'EOF'
## 요약

VOC 1차 도입의 **backend 부분**. PFPlay Web 의 사용자 제출 endpoint + 어드민 콘솔 read-only 조회 endpoint.

- `POST /api/v1/voc/bug-reports` — 자유텍스트 + 자동메타 + 1분 1회 rate-limit
- `GET /api/v1/admin/voc/bug-reports[+/{id}]` — 어드민 목록·상세 (`@adminAuth.isAdmin()`)
- V19 마이그레이션 + `bug_report` 테이블

## 결정 잠금 (사용자, 2026-05-21)

1. 권한 = 멤버 + 게스트 (`isAuthenticated()`)
2. 데이터 = 자유텍스트 5~2000자 + Referer/UA/userId/partyroomId 자동
3. Rate limit = userId 별 1분 1회 (bucket4j + Caffeine)
4. 모듈 = `administration/bug_report` (V13 PartyroomReport sibling)

## 산출물

- 신규 entity/exception/repo/service/controller (15 production files)
- 신규 test 7 (rate-limiter 3 + service 4 + adminService 5 + controller 6 + adminController 5 + IT 5)
- `:app:test` GREEN + `:app:integrationTest` GREEN

## 후속 (별 PR)

- pfplay-admin: 사이드바 "사용자 피드백" + read-only 목록/상세 페이지
- pfplay-web: Header 🐛 버튼 + Dialog + zod 폼 + i18n

## 스펙/계획

- spec: `docs/superpowers/specs/2026-05-21-voc-bug-report-design.md`
- plan: `docs/superpowers/plans/2026-05-21-voc-bug-report.md` Chunk 1
EOF
)"
```

---

## Chunk 2: pfplay-admin (사이드바 + FSD 슬라이스 + 페이지)

Chunk 1 (backend) 머지·dev 배포 후 진입. 단, 작업 자체는 backend MSW handlers 만으로 병렬 가능 — 본 plan 은 사용자 영역 머지 후 직렬화.

### Task 8: pfplay-admin branch 생성 + 사이드바 메뉴 추가

**Files:**
- Modify: `pfplay-admin/src/app/layout.tsx` (사이드바 메뉴 1 항목 추가)

- [ ] **Step 1: branch 생성**

```bash
cd ../pfplay-admin
git fetch origin develop
git checkout -b feature/voc-bug-report origin/develop
```

- [ ] **Step 2: 사이드바 메뉴 1 항목 추가**

`pfplay-admin/src/app/layout.tsx` — `navSections` 배열의 "운영 관리" 섹션 안 마지막 (신고 다음):
```tsx
import { MessageSquareWarning } from 'lucide-react';
// ...
{
  label: '사용자 피드백',
  to: '/voc/bug-reports',
  icon: MessageSquareWarning,
},
```

- [ ] **Step 3: 컴파일·기존 회귀 확인**

Run:
```bash
yarn vitest --run src/app
yarn tsc --noEmit
```
Expected: 기존 layout 테스트 + tsc 모두 GREEN.

- [ ] **Step 4: 커밋**

```bash
git add src/app/layout.tsx
git commit -m "feat(voc): 사이드바 '사용자 피드백' 메뉴 항목 추가 (#voc)"
```

---

### Task 9: `entities/bug-report` + `features/bug-reports` API/model

**Files (FSD 컨벤션, D/#8 정합)**:
- Create: `pfplay-admin/src/entities/bug-report/model/types.ts` — AdminBugReportSummary / Detail
- Create: `pfplay-admin/src/entities/bug-report/api/keys.ts` — react-query keys
- Create: `pfplay-admin/src/entities/bug-report/index.ts`
- Create: `pfplay-admin/src/features/bug-reports/api/list-bug-reports.ts` + test
- Create: `pfplay-admin/src/features/bug-reports/api/get-bug-report-detail.ts` + test
- Create: `pfplay-admin/src/features/bug-reports/model/filter-schema.ts` — zod (period from/to, contentKeyword)
- Create: `pfplay-admin/src/features/bug-reports/model/use-bug-reports-list.hook.ts` + test
- Create: `pfplay-admin/src/features/bug-reports/model/use-bug-report-detail.hook.ts` + test
- Create: `pfplay-admin/test/mocks/fixtures/bug-reports.ts`
- Create: `pfplay-admin/test/mocks/handlers/bug-reports.ts`
- Modify: `pfplay-admin/test/mocks/handlers/index.ts` (bugReportHandlers 등록)

- [ ] **Step 1: D/#8 `entities/guest` + `features/guests` 패턴 미러**

작업 시작 전 `pfplay-admin/src/entities/guest/` + `features/guests/` 파일 구조 한번 열어 정확한 컨벤션 파악 — 본 plan 의 fixtures/handlers/keys/hook 시그니처는 그 patterns 1:1 미러.

- [ ] **Step 2: types 작성**

`pfplay-admin/src/entities/bug-report/model/types.ts`:
```ts
export type AdminBugReportSummary = {
  bugReportId: number;
  reporterUserAccountId: number;
  reporterEmail: string | null;
  reporterNickname: string | null;
  contentPreview: string;
  partyroomId: number | null;
  createdAt: string;
};

export type AdminBugReportDetail = AdminBugReportSummary & {
  content: string;
  pageUrl: string | null;
  userAgent: string | null;
  partyroomName: string | null;
};

export type AdminBugReportListResponse = {
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
  items: AdminBugReportSummary[];
};

export type ListBugReportsQuery = {
  page?: number;
  size?: number;
  sortBy?: 'createdAt';
  direction?: 'ASC' | 'DESC';
  createdFrom?: string;
  createdTo?: string;
  contentKeyword?: string;
};
```

`pfplay-admin/src/entities/bug-report/api/keys.ts`:
```ts
export const bugReportKeys = {
  all: ['bug-reports'] as const,
  list: (query: Record<string, unknown>) => [...bugReportKeys.all, 'list', query] as const,
  detail: (id: number) => [...bugReportKeys.all, 'detail', id] as const,
};
```

`pfplay-admin/src/entities/bug-report/index.ts`:
```ts
export type {
  AdminBugReportSummary,
  AdminBugReportDetail,
  AdminBugReportListResponse,
  ListBugReportsQuery,
} from './model/types';
export { bugReportKeys } from './api/keys';
```

- [ ] **Step 3: MSW fixtures + handlers**

`pfplay-admin/test/mocks/fixtures/bug-reports.ts`:
```ts
import type { AdminBugReportSummary, AdminBugReportDetail } from '@/entities/bug-report';

export const bugReportSummaries: AdminBugReportSummary[] = [
  {
    bugReportId: 1,
    reporterUserAccountId: 100,
    reporterEmail: 'user1@example.com',
    reporterNickname: '닉네임1',
    contentPreview: '재생 중 정지 안 됨',
    partyroomId: 7,
    createdAt: '2026-05-21T10:00:00',
  },
  {
    bugReportId: 2,
    reporterUserAccountId: 101,
    reporterEmail: 'user2@example.com',
    reporterNickname: '닉네임2',
    contentPreview: '아바타 깨짐',
    partyroomId: null,
    createdAt: '2026-05-20T09:00:00',
  },
];

export const bugReportDetail: AdminBugReportDetail = {
  ...bugReportSummaries[0],
  content: '재생 중 정지 버튼을 눌러도 곡이 계속 재생됩니다. 크롬 브라우저에서 발생.',
  pageUrl: 'https://pfplay.xyz/parties/7',
  userAgent: 'Mozilla/5.0 (Windows NT 10.0)',
  partyroomName: '테스트 파티룸',
};
```

`pfplay-admin/test/mocks/handlers/bug-reports.ts`:
```ts
import { http, HttpResponse } from 'msw';
import { bugReportSummaries, bugReportDetail } from '../fixtures/bug-reports';

const ADMIN_API = '*/api/v1/admin/voc/bug-reports';

export const bugReportHandlers = [
  http.get(ADMIN_API, () => {
    return HttpResponse.json({
      data: {
        totalElements: bugReportSummaries.length,
        totalPages: 1,
        page: 0,
        size: 20,
        items: bugReportSummaries,
      },
    });
  }),
  http.get(`${ADMIN_API}/:id`, ({ params }) => {
    const id = Number(params.id);
    if (id === bugReportDetail.bugReportId) {
      return HttpResponse.json({ data: { detail: bugReportDetail } });
    }
    return HttpResponse.json(
      { errorCode: 'BUG-003', message: '버그 리포트를 찾을 수 없습니다' },
      { status: 404 }
    );
  }),
];
```

`pfplay-admin/test/mocks/handlers/index.ts` — 기존 array 에 `...bugReportHandlers` 추가.

- [ ] **Step 4: API + hook 작성 + 테스트**

`pfplay-admin/src/features/bug-reports/api/list-bug-reports.ts`:
```ts
import { httpClient } from '@/shared/api/client';
import type { AdminBugReportListResponse, ListBugReportsQuery } from '@/entities/bug-report';

export async function listBugReports(query: ListBugReportsQuery): Promise<AdminBugReportListResponse> {
  const res = await httpClient.get('/api/v1/admin/voc/bug-reports', { params: query });
  return res.data.data;
}
```

`pfplay-admin/src/features/bug-reports/model/use-bug-reports-list.hook.ts`:
```ts
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { bugReportKeys, type ListBugReportsQuery } from '@/entities/bug-report';
import { listBugReports } from '../api/list-bug-reports';

export function useBugReportsList(query: ListBugReportsQuery) {
  return useQuery({
    queryKey: bugReportKeys.list(query),
    queryFn: () => listBugReports(query),
    placeholderData: keepPreviousData,
  });
}
```

`pfplay-admin/src/features/bug-reports/api/get-bug-report-detail.ts`:
```ts
import { httpClient } from '@/shared/api/client';
import type { AdminBugReportDetail } from '@/entities/bug-report';

export async function getBugReportDetail(id: number): Promise<AdminBugReportDetail> {
  const res = await httpClient.get(`/api/v1/admin/voc/bug-reports/${id}`);
  return res.data.data.detail;
}
```

`pfplay-admin/src/features/bug-reports/model/use-bug-report-detail.hook.ts`:
```ts
import { useQuery } from '@tanstack/react-query';
import { bugReportKeys } from '@/entities/bug-report';
import { getBugReportDetail } from '../api/get-bug-report-detail';

export function useBugReportDetail(id: number) {
  return useQuery({
    queryKey: bugReportKeys.detail(id),
    queryFn: () => getBugReportDetail(id),
    enabled: id > 0,
  });
}
```

Tests (MSW + react-query test wrapper, D/#8 정합) — 본 plan 은 D/#8 의 `useGuestsList.test` 패턴 그대로 미러. 작성 시 실제 패턴 확인.

- [ ] **Step 5: 회귀·통과 확인**

Run:
```bash
yarn vitest --run src/entities/bug-report src/features/bug-reports
yarn tsc --noEmit
```
Expected: 신규 hook + API + types 테스트 PASS.

- [ ] **Step 6: 커밋**

```bash
git add src/entities/bug-report/ src/features/bug-reports/ test/mocks/fixtures/bug-reports.ts test/mocks/handlers/bug-reports.ts test/mocks/handlers/index.ts
git commit -m "feat(voc): entities/bug-report + features/bug-reports slice (API/hooks/MSW) (#voc)"
```

---

### Task 10: `widgets/bug-reports-list` + `widgets/bug-reports-detail`

**Files:**
- Create: `pfplay-admin/src/widgets/bug-reports-list.tsx` + test
- Create: `pfplay-admin/src/widgets/bug-reports-detail.tsx` + test
- Create: `pfplay-admin/src/features/bug-reports/ui/bug-reports-filter-form.tsx` + test (기간 + content keyword)
- Create: `pfplay-admin/src/features/bug-reports/ui/bug-reports-table.tsx` + test

- [ ] **Step 1: D/#8 `widgets/guests-list.tsx` + `guests-detail.tsx` 패턴 미러**

작업 시작 전 그 두 파일 열어 컴포넌트 분리·useUrlQueryState·pagination·row click → navigate 패턴 그대로 미러.

- [ ] **Step 2: FilterForm + Table 작성**

`bug-reports-filter-form.tsx`:
- 기간 from/to (Datepicker, 기존 admin 컴포넌트 재사용)
- contentKeyword Input
- `useUrlQueryState` 로 query string sync

`bug-reports-table.tsx`:
- Columns: 작성자(이메일 + 닉네임 stacked) / 본문 미리보기 / 파티룸 ID / 작성일
- Row click → `useNavigate(/voc/bug-reports/{bugReportId})`
- 빈 결과 placeholder

- [ ] **Step 3: 위젯 조립**

`bug-reports-list.tsx`:
```tsx
import { useUrlQueryState } from '@/shared/lib/use-url-query-state';
import { useBugReportsList } from '@/features/bug-reports/model/use-bug-reports-list.hook';
import { BugReportsFilterForm } from '@/features/bug-reports/ui/bug-reports-filter-form';
import { BugReportsTable } from '@/features/bug-reports/ui/bug-reports-table';
// ... pagination

export function BugReportsList() {
  const [query, setQuery] = useUrlQueryState({ page: 0, size: 20, sortBy: 'createdAt', direction: 'DESC' });
  const { data, isLoading } = useBugReportsList(query);

  return (
    <div>
      <BugReportsFilterForm query={query} onChange={setQuery} />
      <BugReportsTable rows={data?.items ?? []} loading={isLoading} />
      <Pagination total={data?.totalElements ?? 0} page={query.page} size={query.size} onPageChange={(p) => setQuery({ ...query, page: p })} />
    </div>
  );
}
```

`bug-reports-detail.tsx`:
- Cards (작성자 / 본문 전체 white-space:pre-wrap / 컨텍스트 / 메타)
- page_url anchor 가드 (https + 자체 도메인만 click-through, 외부는 텍스트만):
```tsx
function renderPageUrl(url: string | null) {
  if (!url) return '—';
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return url;  // 깨진 URL 텍스트만
  }
  if (parsed.protocol !== 'https:') return url;
  const ownDomains = ['pfplay.xyz', 'admin.pfplay.xyz', 'pfplay.kr'];
  if (ownDomains.includes(parsed.hostname)) {
    return <a href={url} target='_blank' rel='noopener noreferrer'>{url}</a>;
  }
  return url;  // 외부 도메인 텍스트만 (spec §3-7 정합)
}
```
- partyroomId 라벨에 "사용자 신고 시점 주장값" 명시 (Tooltip 또는 small text)

- [ ] **Step 4: 테스트 + 회귀**

테스트 ~7 case (FilterForm 3 + Table 2 + List integration 2 + Detail 3) MSW 활용.

Run:
```bash
yarn vitest --run src/widgets/bug-reports-list src/widgets/bug-reports-detail src/features/bug-reports/ui
yarn tsc --noEmit
```
Expected: 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/widgets/bug-reports-list.tsx src/widgets/bug-reports-detail.tsx src/features/bug-reports/ui/
git commit -m "feat(voc): widgets/bug-reports-{list,detail} + filter/table UI — D/#8 정합 + URL 가드 (#voc)"
```

---

### Task 11: `pages/bug-reports-page` + `pages/bug-report-detail-page` + 라우트 등록

**Files:**
- Create: `pfplay-admin/src/pages/bug-reports-page.tsx`
- Create: `pfplay-admin/src/pages/bug-report-detail-page.tsx`
- Modify: `pfplay-admin/src/App.tsx` (라우트 2개 flat 등록)

- [ ] **Step 1: Page wrappers**

`bug-reports-page.tsx`:
```tsx
import { BugReportsList } from '@/widgets/bug-reports-list';

export function BugReportsPage() {
  return (
    <main>
      <h1>사용자 피드백</h1>
      <BugReportsList />
    </main>
  );
}
```

`bug-report-detail-page.tsx`:
```tsx
import { useParams } from 'react-router-dom';
import { BugReportsDetail } from '@/widgets/bug-reports-detail';

export function BugReportDetailPage() {
  const { bugReportId } = useParams();
  const id = Number(bugReportId);
  if (!Number.isFinite(id) || id <= 0) return <div>잘못된 ID</div>;
  return (
    <main>
      <BugReportsDetail bugReportId={id} />
    </main>
  );
}
```

- [ ] **Step 2: App.tsx 라우트 등록 (flat)**

```tsx
import { BugReportsPage } from '@/pages/bug-reports-page';
import { BugReportDetailPage } from '@/pages/bug-report-detail-page';
// ... existing routes
<Route path='/voc/bug-reports' element={<BugReportsPage />} />
<Route path='/voc/bug-reports/:bugReportId' element={<BugReportDetailPage />} />
```

- [ ] **Step 3: Integration test (MSW)**

`pages/__tests__/bug-reports-page.test.tsx`:
- 목록 페이지 진입 → MSW 응답 → 행 렌더링
- 행 click → navigate 호출
- 필터 적용 → query string 변화

`pages/__tests__/bug-report-detail-page.test.tsx`:
- 정상 id → detail 렌더링
- 404 → 에러 메시지

- [ ] **Step 4: 회귀 + 커밋**

Run:
```bash
yarn vitest --run
yarn tsc --noEmit
```
Expected: 전체 GREEN.

```bash
git add src/pages/bug-reports-page.tsx src/pages/bug-report-detail-page.tsx src/App.tsx src/pages/__tests__/
git commit -m "feat(voc): /admin/voc/bug-reports 페이지 + 상세 + 라우트 등록 (#voc)"
```

---

### Chunk 2 종료 — pfplay-admin PR

- [ ] **Step 1: push + PR 생성**

```bash
git push -u origin feature/voc-bug-report
gh pr create --base develop --title "feat(voc): 사용자 피드백 어드민 콘솔 (read-only)" --body "$(cat <<'EOF'
## 요약

pfplay-platform PR `feat(voc): backend ...` (#{backendPRNumber}) 의 admin frontend. 사이드바 "사용자 피드백" 메뉴 + 목록·상세 페이지. read-only.

## 산출물

- 사이드바 메뉴 1 항목 (운영 관리 섹션 끝, MessageSquareWarning)
- 라우트: `/voc/bug-reports`, `/voc/bug-reports/:bugReportId`
- FSD: entities/bug-report, features/bug-reports, widgets/bug-reports-{list,detail}, pages
- MSW fixtures/handlers
- 테스트 ~15+ case
- URL 가드: page_url anchor 는 https + 자체 도메인만 click-through (spec §3-7)

## 머지 의존성

backend PR 머지 후 dev 배포 + smoke 통과 시 진입 권장 (dev 환경 e2e 가능). 단 본 PR 자체는 MSW 만 의존하므로 backend 미머지 상태에서도 frontend 테스트 GREEN.

## 스펙

`pfplay-platform/docs/superpowers/specs/2026-05-21-voc-bug-report-design.md` §3-7
EOF
)"
```

---

## Chunk 3: pfplay-web (Header 🐛 + Dialog + i18n)

Chunk 1 (backend) 머지·dev 배포 후 진입. 단, MSW 만으로 frontend 테스트 자체는 병렬 가능.

### Task 12: pfplay-web branch + `PFBug` SVG 아이콘 추가

**Files:**
- Create: `pfplay-web/src/shared/ui/icons/pf-bug.tsx`
- Modify: `pfplay-web/src/shared/ui/icons/index.ts` (`PFBug` export)

- [ ] **Step 1: branch 생성**

```bash
cd ../pfplay-web
git fetch origin development
git checkout -b feature/voc-bug-report origin/development
```

> pfplay-web 의 base branch = `development` (`reference_branch_env_mapping` 정합, 2-tier — pfplay-platform 의 `develop` 이름과 다름).

- [ ] **Step 2: SVG 작성**

`pfplay-web/src/shared/ui/icons/pf-bug.tsx`:
```tsx
import { FC } from 'react';

type Props = { width?: number; height?: number; className?: string };

// lucide Bug 시각 미러. 의존성 추가 없이 자체 SVG.
const PFBug: FC<Props> = ({ width = 24, height = 24, className }) => (
  <svg
    width={width}
    height={height}
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    className={className}
    aria-hidden='true'
  >
    <path d='m8 2 1.88 1.88' />
    <path d='M14.12 3.88 16 2' />
    <path d='M9 7.13v-1a3.003 3.003 0 1 1 6 0v1' />
    <path d='M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6' />
    <path d='M12 20v-9' />
    <path d='M6.53 9C4.6 8.8 3 7.1 3 5' />
    <path d='M6 13H2' />
    <path d='M3 21c0-2.1 1.7-3.9 3.8-4' />
    <path d='M20.97 5c0 2.1-1.6 3.8-3.5 4' />
    <path d='M22 13h-4' />
    <path d='M17.2 17c2.1.1 3.8 1.9 3.8 4' />
  </svg>
);

export default PFBug;
```

- [ ] **Step 3: index.ts export**

`pfplay-web/src/shared/ui/icons/index.ts` — 기존 export 옆에:
```ts
export { default as PFBug } from './pf-bug';
```

- [ ] **Step 4: 컴파일 확인 + 커밋**

Run:
```bash
yarn tsc --noEmit
```
Expected: 에러 없음.

```bash
git add src/shared/ui/icons/pf-bug.tsx src/shared/ui/icons/index.ts
git commit -m "feat(voc): PFBug SVG 아이콘 추가 — lucide 미사용 자체 SVG (#voc)"
```

---

### Task 13: `features/bug-report` 슬라이스 (API + schema + hook)

**Files:**
- Create: `pfplay-web/src/features/bug-report/api/submit-bug-report.ts` + test
- Create: `pfplay-web/src/features/bug-report/model/bug-report-schema.ts` (zod)
- Create: `pfplay-web/src/features/bug-report/model/use-submit-bug-report.hook.ts` + test
- Create: `pfplay-web/src/features/bug-report/index.ts`
- Modify: `pfplay-web/src/shared/lib/localization/dictionaries/ko.json`
- Modify: `pfplay-web/src/shared/lib/localization/dictionaries/en.json`

- [ ] **Step 1: i18n 키 추가 (`ko.json` + `en.json`)**

[[feedback_pfplay_web_i18n_drift]] 정합 — **`yarn i18n` 명령 호출 금지**, `ko/en json` 직접 수정. 두 파일 모두 동일 키 구조.

`ko.json`:
```json
"bug_report": {
  "btn": { "open": "버그 제보", "submit": "제출", "cancel": "취소" },
  "title": { "report_bug": "버그 제보" },
  "placeholder": "어떤 버그를 경험하셨나요? (5~2000자)",
  "help": "운영팀이 확인 후 조치합니다. 답변이 어려울 수 있어요.",
  "char_count": "{{current}}/{{max}}",
  "toast": {
    "success": "피드백이 등록되었습니다",
    "rate_limit": "잠시 후 다시 시도해주세요",
    "error": "제출에 실패했어요"
  },
  "validation": {
    "too_short": "최소 5자 이상 입력해주세요",
    "too_long": "최대 2000자까지 입력할 수 있어요"
  }
}
```

`en.json` 동일 구조 영문 번역.

- [ ] **Step 2: zod schema**

`bug-report-schema.ts`:
```ts
import { z } from 'zod';

export const bugReportSchema = z.object({
  content: z
    .string()
    .min(5, { message: 'too_short' })
    .max(2000, { message: 'too_long' }),
});

export type BugReportSchema = z.infer<typeof bugReportSchema>;
```

- [ ] **Step 3: API client + hook**

`submit-bug-report.ts`:
```ts
import { httpClient } from '@/shared/api/http';

export type SubmitBugReportPayload = {
  content: string;
  partyroomId?: number;
};

export type SubmitBugReportResponse = {
  bugReportId: number;
};

export async function submitBugReport(payload: SubmitBugReportPayload): Promise<SubmitBugReportResponse> {
  const res = await httpClient.post('/api/v1/voc/bug-reports', payload);
  return res.data.data;
}
```

`use-submit-bug-report.hook.ts`:
```ts
import { useMutation } from '@tanstack/react-query';
import { usePathname } from 'next/navigation';
import { submitBugReport, type SubmitBugReportPayload } from '../api/submit-bug-report';

function extractPartyroomIdFromPath(pathname: string): number | undefined {
  const match = pathname.match(/^\/parties\/(\d+)/);
  if (!match) return undefined;
  const id = Number(match[1]);
  return Number.isFinite(id) && id > 0 ? id : undefined;
}

export function useSubmitBugReport() {
  const pathname = usePathname();
  return useMutation({
    mutationFn: (input: { content: string }) => {
      const payload: SubmitBugReportPayload = {
        content: input.content,
        partyroomId: extractPartyroomIdFromPath(pathname),
      };
      return submitBugReport(payload);
    },
  });
}
```

- [ ] **Step 4: 테스트 작성**

`submit-bug-report.test.ts` (MSW) — 201 / 400 / 429 case.

`use-submit-bug-report.test.ts` — pathname `/parties/7` → partyroomId 7, `/parties/abc` → undefined, `/` → undefined.

- [ ] **Step 5: 회귀 + 커밋**

Run:
```bash
yarn vitest --run src/features/bug-report src/shared/lib/localization
yarn tsc --noEmit
```
Expected: 신규 + 기존 i18n 회귀 GREEN. [[feedback_pfplay_web_i18n_drift]] 위생 자동 가드.

```bash
git add src/features/bug-report/api/ src/features/bug-report/model/ src/shared/lib/localization/dictionaries/
git commit -m "feat(voc): features/bug-report slice — submit API + zod + partyroomId 추출 + i18n (#voc)"
```

---

### Task 14: `BugReportForm` + `BugReportDialog` + 신규 mini-toast

**Files:**
- Create: `pfplay-web/src/features/bug-report/ui/bug-report-toast.tsx` — 신규 mini-toast (EventToast 스타일 미러, 의존성 추가 없이)
- Create: `pfplay-web/src/features/bug-report/model/use-bug-report-toast.hook.ts` — zustand store (success/error/rate_limit + 자동 dismiss 3s)
- Create: `pfplay-web/src/features/bug-report/ui/bug-report-form.component.tsx` + test
- Create: `pfplay-web/src/features/bug-report/ui/bug-report-dialog.component.tsx` + test
- Create: `pfplay-web/src/features/bug-report/index.ts` (BugReportButton/Dialog 외부 노출)

> **Toast 사정 (사용자 결정 2026-05-21)**: pfplay-web 전역 toast 라이브러리 부재 (`@/shared/lib/toast` 미존재, 기존 sign-in 은 `alert()` 사용). 결정 = **VOC 전용 mini-toast 신규 도입** — system-announcement `EventToast` 의 스타일·a11y (`role='status'`, severity accent, dismissible) 컨벤션 미러하되 broadcast 가 아닌 단일 사용자 액션 응답 용도 (severity = success/error 2종). zustand store + 3초 auto-dismiss. 외부 의존성 0.

- [ ] **Step 1: 실패 form 테스트 작성**

`bug-report-form.component.test.tsx`:
- 초기 disabled (content 비어있음)
- 4자 입력 시 disabled + error 메시지 표시
- 5자 입력 시 enable
- 2001자 입력 시 error
- 제출 → mutation 호출 + 성공 toast + onSubmitted callback 호출
- 429 응답 → rate_limit toast
- 그 외 에러 → 일반 error toast

- [ ] **Step 2: 실패 확인**

Run:
```bash
yarn vitest --run src/features/bug-report/ui/bug-report-form
```
Expected: 컴파일 또는 import 실패.

- [ ] **Step 2.5: mini-toast store + component 작성**

`pfplay-web/src/features/bug-report/model/use-bug-report-toast.hook.ts`:
```ts
import { create } from 'zustand';

type Severity = 'success' | 'error';
type ToastItem = { severity: Severity; message: string };

type State = {
  current: ToastItem | null;
  show: (item: ToastItem) => void;
  dismiss: () => void;
};

export const useBugReportToast = create<State>((set) => ({
  current: null,
  show: (item) => {
    set({ current: item });
    setTimeout(() => set((s) => (s.current === item ? { current: null } : s)), 3000);
  },
  dismiss: () => set({ current: null }),
}));
```

`pfplay-web/src/features/bug-report/ui/bug-report-toast.tsx` (EventToast 스타일 미러):
```tsx
'use client';
import { cn } from '@/shared/lib/functions/cn';
import { Typography } from '@/shared/ui/components/typography';
import { useBugReportToast } from '../model/use-bug-report-toast.hook';

const ACCENT: Record<string, string> = {
  success: 'border-l-green-400',
  error:   'border-l-red-300',
};

export function BugReportToast() {
  const current = useBugReportToast((s) => s.current);
  const dismiss = useBugReportToast((s) => s.dismiss);
  if (!current) return null;
  return (
    <div
      data-testid='bug-report-toast'
      role='status'
      className={cn(
        'pointer-events-auto w-[320px] bg-gray-800 border border-gray-700 rounded-[6px] border-l-[3px] shadow-lg',
        ACCENT[current.severity]
      )}
    >
      <div className='px-4 py-3 flex items-start gap-3'>
        <Typography type='detail2' className='text-gray-50 flex-1'>{current.message}</Typography>
        <button
          type='button'
          onClick={dismiss}
          data-testid='bug-report-toast-close'
          className='text-gray-400 hover:text-gray-200 leading-none px-1 -mt-0.5'
        >
          ×
        </button>
      </div>
    </div>
  );
}
```

> BugReportToast 렌더 위치: BugReportDialog Body 안 `BugReportForm` 옆 (모달 내부 inline). dialog 닫혀도 store 자동 dismiss 됨. 별도 `<BugReportToast />` host 가 외부 layout 에 필요 없음 — 사용자 결정상 1차 도입 단순화. 후속에 dialog 외부 fixed position 토스트 host 필요 시 별 PR.

- [ ] **Step 3: `BugReportForm` 구현**

```tsx
'use client';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { Button } from '@/shared/ui/components/button';
import { TextButton } from '@/shared/ui/components/text-button';
import { Textarea } from '@/shared/ui/components/textarea';
import { Typography } from '@/shared/ui/components/typography';
import { useI18n } from '@/shared/lib/localization/i18n.context';
import { useBugReportToast } from '../model/use-bug-report-toast.hook';  // VOC 전용 mini-toast (Task 14 신규)
import { bugReportSchema, type BugReportSchema } from '../model/bug-report-schema';
import { useSubmitBugReport } from '../model/use-submit-bug-report.hook';

const CONTENT_MAX = 2000;

type Props = {
  onSubmitted: () => void;
};

export function BugReportForm({ onSubmitted }: Props) {
  const t = useI18n();
  const { register, handleSubmit, watch, formState } = useForm<BugReportSchema>({
    resolver: zodResolver(bugReportSchema),
    mode: 'onChange',
  });
  const content = watch('content', '');
  const mutation = useSubmitBugReport();
  const toast = useBugReportToast();

  const onSubmit = (data: BugReportSchema) => {
    mutation.mutate(
      { content: data.content },
      {
        onSuccess: () => {
          toast.show({ severity: 'success', message: t.bug_report.toast.success });
          onSubmitted();
        },
        onError: (err: unknown) => {
          const status = (err as { response?: { status?: number } })?.response?.status;
          toast.show({
            severity: 'error',
            message: status === 429 ? t.bug_report.toast.rate_limit : t.bug_report.toast.error,
          });
        },
      }
    );
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className='flex flex-col gap-4'>
      <Textarea
        {...register('content')}
        rows={6}
        placeholder={t.bug_report.placeholder}
        maxLength={CONTENT_MAX}
        aria-label={t.bug_report.title.report_bug}
        data-testid='bug-report-content'
      />
      <div className='flex justify-between'>
        <Typography type='detail2' className='text-gray-300'>
          {t.bug_report.help}
        </Typography>
        <Typography type='detail2' className={content.length > CONTENT_MAX * 0.9 ? 'text-red-400' : 'text-gray-300'}>
          {content.length}/{CONTENT_MAX}
        </Typography>
      </div>
      {formState.errors.content && (
        <Typography type='detail2' className='text-red-400'>
          {/* i18n key resolves: bug_report.validation.{too_short|too_long} */}
          {t.bug_report.validation[formState.errors.content.message as 'too_short' | 'too_long']}
        </Typography>
      )}
      <div className='flex justify-end gap-3'>
        <TextButton onClick={onSubmitted} type='button'>{t.bug_report.btn.cancel}</TextButton>
        <Button
          type='submit'
          color='primary'
          disabled={!formState.isValid || mutation.isPending}
          data-testid='bug-report-submit'
        >
          {t.bug_report.btn.submit}
        </Button>
      </div>
    </form>
  );
}
```

- [ ] **Step 4: `BugReportDialog` (openDialog wrapper)**

```tsx
'use client';
import { useDialog } from '@/shared/ui/components/dialog';
import { Typography } from '@/shared/ui/components/typography';
import { useI18n } from '@/shared/lib/localization/i18n.context';
import { BugReportForm } from './bug-report-form.component';
import { BugReportToast } from './bug-report-toast';

export function useOpenBugReportDialog() {
  const { openDialog } = useDialog();
  const t = useI18n();

  return () =>
    openDialog((_, onCancel) => ({
      title: ({ defaultClassName }) => (
        <Typography type='title2' className={defaultClassName}>
          {t.bug_report.title.report_bug}
        </Typography>
      ),
      titleAlign: 'left',
      showCloseIcon: true,
      classNames: { container: 'w-[480px] py-7 px-8 bg-black' },
      Body: (
        <>
          <BugReportToast />
          <BugReportForm onSubmitted={onCancel ?? (() => {})} />
        </>
      ),
    }));
}
```

- [ ] **Step 5: 통과 + 커밋**

Run:
```bash
yarn vitest --run src/features/bug-report/ui
```
Expected: 7+ cases PASS.

```bash
git add src/features/bug-report/ui/
git commit -m "feat(voc): BugReportForm + Dialog — useDialog + react-hook-form + zod (#voc)"
```

---

### Task 15: `BugReportButton` + Header 통합

**Files:**
- Create: `pfplay-web/src/features/bug-report/ui/bug-report-button.component.tsx` + test
- Modify: `pfplay-web/src/widgets/layouts/ui/header.component.tsx` (1줄 추가, GT 분기 바깥)

- [ ] **Step 1: 실패 button 테스트 작성**

`bug-report-button.component.test.tsx`:
- aria-label 정확
- 클릭 → openDialog 호출 (`useDialog` mock)
- 게스트(GT) 도 노출 확인 (Header integration test 에서 또 한번 회귀 가드)

- [ ] **Step 2: 구현**

`bug-report-button.component.tsx`:
```tsx
'use client';
import { useI18n } from '@/shared/lib/localization/i18n.context';
import { PFBug } from '@/shared/ui/icons';
import { useOpenBugReportDialog } from './bug-report-dialog.component';

export function BugReportButton() {
  const t = useI18n();
  const openDialog = useOpenBugReportDialog();

  return (
    <button
      type='button'
      onClick={openDialog}
      aria-label={t.bug_report.btn.open}
      className='text-gray-400 hover:text-gray-200 transition-colors'
      data-testid='bug-report-button'
    >
      <PFBug width={24} height={24} />
    </button>
  );
}
```

- [ ] **Step 3: Header 통합 (1줄, GT 분기 바깥)**

`header.component.tsx` line 53 의 `<div className='items-center gap-6 flexRow'>` 안 — **`me && me.authorityTier !== AuthorityTier.GT` Menu 블록 바깥**, `<LanguageChangeMenu />` 와 sibling:

```diff
        <div className='items-center gap-6 flexRow'>
          {me && me.authorityTier !== AuthorityTier.GT && (
            <Menu as='section' className={`relative w-fit`}>
              {/* ... */}
            </Menu>
          )}

+         {me && <BugReportButton />}
          <LanguageChangeMenu />
        </div>
```

> `{me && <BugReportButton />}` — useFetchMe 가 resolve 된 뒤(authenticated 사용자) 만 표시. GT 게스트 포함. 미인증(me=null) 시 hidden.

import 1줄 추가: `import { BugReportButton } from '@/features/bug-report';`

`features/bug-report/index.ts`:
```ts
export { BugReportButton } from './ui/bug-report-button.component';
```

- [ ] **Step 4: Header 회귀 테스트**

`header.component.test.tsx` 에 case 추가:
- 게스트(GT)일 때 BugReportButton 표시 + user Menu 부재
- 멤버일 때 BugReportButton 표시 + user Menu 표시
- 미인증(me=null) 시 BugReportButton 부재

- [ ] **Step 5: 회귀·통과 확인**

Run:
```bash
yarn vitest --run src/features/bug-report src/widgets/layouts
yarn tsc --noEmit
```
Expected: 전체 GREEN.

- [ ] **Step 6: 커밋**

```bash
git add src/features/bug-report/ui/bug-report-button.component.tsx src/features/bug-report/index.ts \
        src/widgets/layouts/ui/header.component.tsx src/widgets/layouts/ui/header.component.test.tsx
git commit -m "feat(voc): Header BugReportButton — GT 분기 바깥, useFetchMe 게이트 (#voc)"
```

---

### Chunk 3 종료 — pfplay-web PR

- [ ] **Step 1: 전체 회귀 + push + PR**

Run:
```bash
yarn vitest --run
yarn tsc --noEmit
yarn lint
```
Expected: 전 GREEN.

```bash
git push -u origin feature/voc-bug-report
gh pr create --base development --title "feat(voc): 버그 제보 버튼 + Dialog (Header)" --body "$(cat <<'EOF'
## 요약

pfplay-platform PR (#{backendPRNumber}) 의 web frontend. Header 우측 🐛 버튼 + 자유텍스트 모달.

## 산출물

- `PFBug` SVG 아이콘 (lucide 미사용 자체 SVG)
- `features/bug-report` slice (API + zod schema + react-query mutation + Form + Dialog + Button)
- Header 1줄 (GT 분기 바깥, useFetchMe 게이트 — 게스트 포함 전 인증 사용자 노출)
- i18n ko/en 키 (`bug_report.*`) — 직접 json 수정 (yarn i18n 안 함, [[feedback_pfplay_web_i18n_drift]])
- 테스트 ~15+ case (form / dialog / button / header 회귀)
- 자동 메타: page_url(Referer 헤더 → 백엔드), user_agent(헤더 → 백엔드), partyroomId(`usePathname` `/parties/{id}` 매칭)

## 머지 의존성

backend PR 머지 + dev 배포 + smoke 통과 후 진입 권장. (단 본 PR 자체는 MSW 만 의존하므로 테스트 GREEN.)

## 스펙

`pfplay-platform/docs/superpowers/specs/2026-05-21-voc-bug-report-design.md` §3-6
EOF
)"
```

---

## 최종 회귀 확인 (3 레포)

- [ ] **Step 1: pfplay-platform**

```bash
cd ../pfplay-platform
JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test :app:integrationTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: pfplay-admin**

```bash
cd ../pfplay-admin
yarn vitest --run && yarn tsc --noEmit
```
Expected: 전 GREEN.

- [ ] **Step 3: pfplay-web**

```bash
cd ../pfplay-web
yarn vitest --run && yarn tsc --noEmit && yarn lint
```
Expected: 전 GREEN.

---

## 머지·배포 순서 (사용자 영역)

1. pfplay-platform PR develop merge → deploy-dev workflow 트리거
2. dev smoke: `curl POST /api/v1/voc/bug-reports` 201 + admin `GET /api/v1/admin/voc/bug-reports` 200
3. pfplay-admin PR develop merge → admin dev 배포
4. pfplay-web PR development merge → web dev 배포
5. 통합 e2e: web 제출 → admin 조회 확인
6. release(stg) 격상 — 3 레포 별건 release PR (사용자 영역)
7. prod(main) 승격 — squash ([[feedback_main_squash_merge]])

---

## 참고

- spec: `docs/superpowers/specs/2026-05-21-voc-bug-report-design.md` (reviewer 2-round Approved)
- 인접 작업: V13 `partyroom_report` (자유텍스트 신고 백엔드 패턴) · D/#8 admin GUEST (FSD read-only 패턴) · `AdminLoginRateLimiter` (bucket4j 패턴)
- 메모리: [[feedback_pr_series_workflow]], [[feedback_commit_consolidation_before_push]], [[feedback_korean_issue_commit_pr]], [[feedback_elegant_no_code_dirtying]], [[feedback_pfplay_web_i18n_drift]], [[reference_pfplay_platform_jdk]], [[project_jvm_tz_kst_policy]], [[reference_mysql_datetime0_rounding]], [[single-partyroom-subscription-invariant]]
