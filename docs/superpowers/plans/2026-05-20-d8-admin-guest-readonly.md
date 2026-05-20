# D/#8 — 어드민 GUEST read-only 조회 구현 계획

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민 콘솔에 GUEST 사용자 목록·상세 read-only view 를 별도 탭으로 추가한다. MEMBER 코드 무수정, 회귀 zero.

**Architecture:** backend = `administration` BC 내부에 `AdminGuest*` 5종 신규(controller/service/repository/impl/exception + DTO 5종), MEMBER 패턴 동형. frontend = `entities/guest` + `features/guests` 슬라이스 신설 + `widgets/guests-list.tsx` + `pages/members-page.tsx` 를 Tabs 컨테이너로 전환. DB schema·env 무변경.

**Tech Stack:** Java 21 (Gradle 호출 시 `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7"` prefix 필수), Spring Boot, JPA, QueryDSL, JUnit5 + Mockito. Frontend: TypeScript, React 18, React Router v6, React Query v5, vitest, Testing Library.

**Spec:** `docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md`

**Branches:**
- backend = `feature/d8-admin-guest-readonly` (pfplay-platform, 이미 생성됨, spec 커밋 존재)
- frontend = `feature/d8-admin-guest-readonly` (pfplay-admin, Chunk 2 시작 시 생성)

---

## File Structure

### Backend (pfplay-platform `app` 모듈)

**신규**:
- `app/.../administration/adapter/in/web/AdminGuestQueryController.java`
- `app/.../administration/adapter/in/web/dto/AdminGuestListQuery.java`
- `app/.../administration/adapter/in/web/dto/AdminGuestSummaryResponse.java`
- `app/.../administration/adapter/in/web/dto/AdminGuestDetailResponse.java`
- `app/.../administration/adapter/in/web/dto/GuestProfileSummary.java`
- `app/.../administration/application/service/AdminGuestQueryService.java`
- `app/.../administration/adapter/out/persistence/AdminGuestQueryRepository.java`
- `app/.../administration/adapter/out/persistence/impl/AdminGuestQueryRepositoryImpl.java`
- `app/.../administration/adapter/out/persistence/dto/AdminGuestSummaryRow.java`
- `app/.../administration/adapter/out/persistence/dto/AdminGuestDetailRow.java`
- `app/.../administration/domain/exception/AdminGuestException.java`
- 테스트: `AdminGuestQueryControllerTest.java`, `AdminGuestQueryServiceTest.java`, `AdminGuestQueryRepositoryImplIT.java`

**수정**:
- `app/.../admin/adapter/in/web/AbstractAdminWebMvcTest.java` — `@WebMvcTest` 배열에 `AdminGuestQueryController.class` 추가, `@MockBean protected AdminGuestQueryService adminGuestQueryService;` 추가

### Frontend (pfplay-admin)

**신규**:
- `src/entities/guest/index.ts`
- `src/entities/guest/model/types.ts`
- `src/features/guests/api/guests-api.ts`
- `src/features/guests/api/use-guests-list.ts`
- `src/features/guests/api/use-guest-detail.ts`
- `src/features/guests/api/__tests__/guests-api.test.ts`
- `src/features/guests/api/__tests__/use-guests-list.test.tsx`
- `src/features/guests/api/__tests__/use-guest-detail.test.tsx`
- `src/features/guests/model/filter-schema.ts`
- `src/features/guests/model/__tests__/filter-schema.test.ts`
- `src/features/guests/ui/guests-table.tsx`
- `src/features/guests/ui/guests-filter-form.tsx`
- `src/features/guests/ui/guest-detail-cards.tsx`
- `src/features/guests/ui/__tests__/guests-table.test.tsx`
- `src/features/guests/ui/__tests__/guests-filter-form.test.tsx`
- `src/features/guests/ui/__tests__/guest-detail-cards.test.tsx`
- `src/widgets/guests-list.tsx`
- `src/pages/guest-detail-page.tsx`
- `src/pages/__tests__/guest-detail-page.test.tsx`

**수정**:
- `src/pages/members-page.tsx` — Tabs 컨테이너로 전환 (기존 5줄 wrapper → ~25줄)
- `src/pages/__tests__/members-page.test.tsx` (기존이 있다면 확장, 없으면 신규) — 탭 ↔ URL sync, 회귀 가드
- `src/app/routes.tsx` (또는 라우트 등록 파일) — `/guests/:guestId` route 추가
- (필요 시) `src/shared/api/page.ts` import 재사용 — 무수정

### 빌드/검증 명령 (Windows + Git Bash)

**Backend**: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "<FQN>"`

**Backend 전체**: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test`

**Frontend**: `yarn vitest run <pattern>` 또는 `yarn vitest run` (전체)

**Frontend 타입체크**: `yarn tsc --noEmit`

**Frontend 린트**: `yarn lint`

---

## Chunk 1: Backend — DTOs · Exception · Repository · Service · Controller · 테스트

> 단일 chunk 로 backend 전부 통합. MEMBER 코드 무수정 invariant 가 chunk 내내 유지되어야 함. 모든 신규 클래스는 `AdminMember*` 동형 패턴 직역. **review 검증포인트**: ① MEMBER 코드 무수정, ② `AbstractAdminWebMvcTest` 외 모든 변경은 신규 파일, ③ ArchUnit 추가 룰 없음.

### Task 1: 신규 도메인 예외 `AdminGuestException`

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdminGuestException.java`

기존 `AdminMemberException` 과 동형 패턴 — error code prefix `GST`.

- [ ] **Step 1: 신규 파일 작성**

```java
package com.pfplaybackend.api.administration.domain.exception;

import com.pfplaybackend.api.common.exception.DomainException;
import com.pfplaybackend.api.common.exception.ErrorType;
import lombok.Getter;

/**
 * Guest 어드민 도메인 예외. Codes: GST-NNN.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §9.1.
 * MEMBER 와 동형 패턴(error code prefix 만 GST). MUTATION 부재 (read-only) 라
 * NOT_FOUND + INVALID_LIST_QUERY 두 코드만.
 */
@Getter
public enum AdminGuestException implements DomainException {

    GUEST_NOT_FOUND("GST-001", "Guest 가 존재하지 않습니다.", ErrorType.NOT_FOUND),
    INVALID_LIST_QUERY("GST-002", "Guest 목록 조회 query 파라미터가 유효하지 않습니다.", ErrorType.BAD_REQUEST);

    private final String errorCode;
    private final String message;
    private final ErrorType errorType;

    AdminGuestException(String errorCode, String message, ErrorType errorType) {
        this.errorCode = errorCode;
        this.message = message;
        this.errorType = errorType;
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/domain/exception/AdminGuestException.java
git commit -m "feat(d8): AdminGuestException — NOT_FOUND/INVALID_LIST_QUERY (#8)"
```

### Task 2: DTO 5종 (List query · Summary · Detail · GuestProfileSummary · Persistence rows)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminGuestListQuery.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminGuestSummaryResponse.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminGuestDetailResponse.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/GuestProfileSummary.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/dto/AdminGuestSummaryRow.java`
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/dto/AdminGuestDetailRow.java`

기존 `UserAccountSummary` / `RecentActivityLogItem` 은 재사용 (administration BC 내부 공용 DTO).

- [ ] **Step 1: `AdminGuestListQuery` 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import java.time.LocalDate;

/**
 * GET /admin/guests query parameters. tier 필드 부재 (guest 는 항상 GT — Spec §3, §6.1).
 *
 * <p>sort 허용 값은 AdminMemberListQuery 와 동일 상수값을 의도적으로 공유 (UI 정렬 옵션 통일).
 * size cap 은 Controller validation.
 */
public record AdminGuestListQuery(
        String email,
        LocalDate joinedFrom,
        LocalDate joinedTo,
        String sort
) {
    public static final String SORT_CREATED_AT_DESC = "created_at_desc";
    public static final String SORT_CREATED_AT_ASC = "created_at_asc";
    public static final String SORT_LAST_ACTIVITY_DESC = "last_activity_desc";
}
```

- [ ] **Step 2: `GuestProfileSummary` 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

/**
 * GET /admin/guests/{guestId} detail response — profile sub-payload.
 * MemberProfileSummary 와 shape 동일하나, guest-specific 필드 향후 추가 시 영향 격리를 위해 분리.
 * 두 필드 모두 nullable: guest 가 isProfileUpdated=false 면 미존재.
 */
public record GuestProfileSummary(
        String nickname,
        String introduction
) {}
```

- [ ] **Step 3: `AdminGuestSummaryResponse` 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;

import java.time.LocalDateTime;

/**
 * GET /admin/guests 응답 row payload.
 *
 * <p>Service layer 가 {@link com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestSummaryRow}
 * 를 받아 {@code withdrawn} flag 를 {@code withdrawnAt != null} 로 derive 해서 채운다
 * (Member A-1 패턴과 동일 — Spec §5.3).
 */
public record AdminGuestSummaryResponse(
        Long guestId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        String nickname,
        String agent,
        boolean isProfileUpdated,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        boolean withdrawn,
        LocalDateTime withdrawnAt
) {}
```

- [ ] **Step 4: `AdminGuestDetailResponse` 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /admin/guests/{guestId} response payload.
 *
 * <p>Composed by AdminGuestQueryService from two sources:
 *  - AdminGuestQueryRepository.findDetail — guest + linked user_account row.
 *  - UserActivityLogRepository.findTop30... — recent 30 activity log rows (Member 와 재사용).
 *
 * <p>{@code withdrawn} flag 는 Member detail 과 동일하게 root level 에 노출 (list/detail 일관성).
 */
public record AdminGuestDetailResponse(
        Long guestId,
        UserAccountSummary userAccount,
        GuestProfileSummary profile,
        String agent,
        boolean isProfileUpdated,
        LocalDateTime createdAt,
        boolean withdrawn,
        LocalDateTime withdrawnAt,
        List<RecentActivityLogItem> recentActivityLog
) {}
```

- [ ] **Step 5: `AdminGuestSummaryRow` 작성** (persistence projection)

```java
package com.pfplaybackend.api.administration.adapter.out.persistence.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;

import java.time.LocalDateTime;

/**
 * AdminGuestQueryRepository.search 의 Projection — guest + linked user_account 합본.
 * Service 가 {@code withdrawn} flag 를 {@code withdrawnAt != null} 로 derive 해서
 * {@code AdminGuestSummaryResponse} 로 매핑한다.
 */
public record AdminGuestSummaryRow(
        Long guestId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        String nickname,
        String agent,
        boolean isProfileUpdated,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime withdrawnAt
) {}
```

- [ ] **Step 6: `AdminGuestDetailRow` 작성**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence.dto;

import com.pfplaybackend.api.common.config.security.enums.ProviderType;

import java.time.LocalDateTime;

/**
 * AdminGuestQueryRepository.findDetail 의 Projection.
 * recentActivityLog 는 service 가 UserActivityLogRepository 호출로 별도 합성.
 */
public record AdminGuestDetailRow(
        Long guestId,
        Long userAccountId,
        String email,
        ProviderType providerType,
        LocalDateTime lastLoginAt,
        LocalDateTime withdrawnAt,
        String nickname,
        String introduction,
        String agent,
        boolean isProfileUpdated,
        LocalDateTime createdAt
) {}
```

- [ ] **Step 7: 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminGuestListQuery.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/GuestProfileSummary.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminGuestSummaryResponse.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/dto/AdminGuestDetailResponse.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/dto/AdminGuestSummaryRow.java \
        app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/dto/AdminGuestDetailRow.java
git commit -m "feat(d8): AdminGuest DTO 6종 — list query / summary / detail / profile / row 2종 (#8)"
```

### Task 3: Repository interface

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminGuestQueryRepository.java`

- [ ] **Step 1: interface 작성**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestDetailRow;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestSummaryRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * Guest 어드민 query repository (read-only).
 * QueryDSL 구현 — AdminMemberQueryRepository 패턴 동형.
 *
 * <p>Cross-BC: implementation 만 user BC entity ({@code GuestData}, {@code UserAccountData}) 참조
 * (administration BC 내 ArchUnit 룰: repository impl 만 entity import 허용).
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §6.
 */
public interface AdminGuestQueryRepository {

    /**
     * guest + linked userAccount join 으로 detail row 1건 조회.
     * recentActivityLog 는 별도 UserActivityLogRepository 호출 (service orchestration).
     */
    Optional<AdminGuestDetailRow> findDetail(Long guestId);

    /**
     * filter(email LIKE / 가입일 range) + sort(created_at asc/desc, last_activity_desc) +
     * pagination. tier filter 부재 (guest 는 항상 GT).
     *
     * <p>{@code last_activity_desc}: user_activity_log MAX(occurredAt) 에 활동 0건인 guest 는
     * fallback 으로 user_account.createdAt 사용 — LEFT JOIN + COALESCE 패턴
     * (AdminMemberQueryRepositoryImpl 와 동형).
     */
    Page<AdminGuestSummaryRow> search(AdminGuestListQuery query, Pageable pageable);
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/AdminGuestQueryRepository.java
git commit -m "feat(d8): AdminGuestQueryRepository interface (#8)"
```

### Task 4: Repository impl — `AdminGuestQueryRepositoryImpl` (QueryDSL)

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminGuestQueryRepositoryImpl.java`

`AdminMemberQueryRepositoryImpl` 와 동형. 차이점:
- `from(guestData)` + leftJoin `userAccountData` on `userAccountData.userId.uid.eq(guestData.userAccountId)`
- `tier` 필터 없음
- 추가 컬럼: `guestData.agent`, `guestData.isProfileUpdated`
- nickname `cast(... as string)` 패턴 동일

- [ ] **Step 1: 신규 파일 작성**

```java
package com.pfplaybackend.api.administration.adapter.out.persistence.impl;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminGuestQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestDetailRow;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestSummaryRow;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.pfplaybackend.api.administration.domain.entity.QUserActivityLogData.userActivityLogData;
import static com.pfplaybackend.api.user.domain.entity.data.QGuestData.guestData;
import static com.pfplaybackend.api.user.domain.entity.data.QUserAccountData.userAccountData;

/**
 * QueryDSL impl of {@link AdminGuestQueryRepository}.
 *
 * <p>AdminMemberQueryRepositoryImpl 패턴 동형 (member→guest 치환):
 *  - from(guestData) + leftJoin(userAccountData) on user_account_id = uid
 *  - nickname cast(... as string) — NicknameConverter 우회 (Member 와 동일 trick)
 *  - last_activity_desc: userActivityLogData leftJoin + GROUP BY + COALESCE fallback
 *
 * <p>Cross-BC entity reference (User) is allowed only inside this adapter — ArchUnit
 * 기존 룰 자동 적용 (별도 룰 추가 없음).
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §6.
 */
@Repository
@RequiredArgsConstructor
public class AdminGuestQueryRepositoryImpl implements AdminGuestQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<AdminGuestDetailRow> findDetail(Long guestId) {
        StringExpression nicknameAsString = Expressions.stringTemplate(
                "cast({0} as string)", guestData.profileData.bio.nickname);

        AdminGuestDetailRow row = queryFactory
                .select(Projections.constructor(AdminGuestDetailRow.class,
                        guestData.guestId,
                        guestData.userAccountId,
                        userAccountData.email,
                        userAccountData.providerType,
                        userAccountData.lastLoginAt,
                        userAccountData.withdrawnAt,
                        nicknameAsString,
                        guestData.profileData.bio.introduction,
                        guestData.agent,
                        guestData.isProfileUpdated,
                        userAccountData.createdAt))
                .from(guestData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(guestData.userAccountId))
                .where(guestData.guestId.eq(guestId))
                .fetchOne();
        return Optional.ofNullable(row);
    }

    @Override
    public Page<AdminGuestSummaryRow> search(AdminGuestListQuery query, Pageable pageable) {
        StringExpression nicknameAsString = Expressions.stringTemplate(
                "cast({0} as string)", guestData.profileData.bio.nickname);

        BooleanBuilder where = new BooleanBuilder();
        if (query.email() != null && !query.email().isBlank()) {
            where.and(userAccountData.email.containsIgnoreCase(query.email()));
        }
        if (query.joinedFrom() != null) {
            where.and(userAccountData.createdAt.goe(query.joinedFrom().atStartOfDay()));
        }
        if (query.joinedTo() != null) {
            where.and(userAccountData.createdAt.lt(query.joinedTo().plusDays(1).atStartOfDay()));
        }

        JPAQuery<AdminGuestSummaryRow> baseQuery = queryFactory
                .select(Projections.constructor(AdminGuestSummaryRow.class,
                        guestData.guestId,
                        guestData.userAccountId,
                        userAccountData.email,
                        userAccountData.providerType,
                        nicknameAsString,
                        guestData.agent,
                        guestData.isProfileUpdated,
                        userAccountData.lastLoginAt,
                        userAccountData.createdAt,
                        userAccountData.withdrawnAt))
                .from(guestData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(guestData.userAccountId))
                .where(where);

        if (AdminGuestListQuery.SORT_LAST_ACTIVITY_DESC.equals(query.sort())) {
            baseQuery
                    .leftJoin(userActivityLogData)
                    .on(userActivityLogData.userAccountId.eq(guestData.userAccountId))
                    .groupBy(guestData.guestId,
                            guestData.userAccountId,
                            userAccountData.email,
                            userAccountData.providerType,
                            guestData.profileData.bio.nickname,
                            guestData.agent,
                            guestData.isProfileUpdated,
                            userAccountData.lastLoginAt,
                            userAccountData.createdAt,
                            userAccountData.withdrawnAt)
                    .orderBy(
                            userActivityLogData.occurredAt.max()
                                    .coalesce(userAccountData.createdAt).desc(),
                            guestData.guestId.desc());
        } else if (AdminGuestListQuery.SORT_CREATED_AT_ASC.equals(query.sort())) {
            baseQuery.orderBy(userAccountData.createdAt.asc(), guestData.guestId.asc());
        } else {
            // default: created_at_desc (null sort 포함)
            baseQuery.orderBy(userAccountData.createdAt.desc(), guestData.guestId.desc());
        }

        List<AdminGuestSummaryRow> rows = baseQuery
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(guestData.count())
                .from(guestData)
                .leftJoin(userAccountData).on(userAccountData.userId.uid.eq(guestData.userAccountId))
                .where(where)
                .fetchOne();

        return new PageImpl<>(rows, pageable, total != null ? total : 0L);
    }
}
```

- [ ] **Step 2: Q-class 생성 + 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`
Expected: BUILD SUCCESSFUL. `QGuestData`, `QUserAccountData`, `QUserActivityLogData` 생성 확인 (이미 존재 — Member 에서 사용 중인 패턴).

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminGuestQueryRepositoryImpl.java
git commit -m "feat(d8): AdminGuestQueryRepositoryImpl — QueryDSL guest+user_account join (#8)"
```

### Task 5: Repository IT — `AdminGuestQueryRepositoryImplIT`

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminGuestQueryRepositoryImplIT.java`

기존 `AdminMemberQueryRepositoryImplIT` 패턴 직역. 5 guest seed (FOO 1·2, BAR 1·2·3), 1명 activity 5건, 나머지 0건.

- [ ] **Step 1: 실패 IT 작성** (test 부분만 발췌, 클래스 골격 + seedGuest 헬퍼는 Member IT 의 seedMember 패턴 동형 — guest 테이블·profile_data 셋업)

기존 `AdminMemberQueryRepositoryImplIT` 의 `seedMember`, `@AfterEach cleanup`, `seedActivities` 헬퍼를 guest 용으로 치환. **Profile (bio.nickname) 동봉** 필요 — `GuestData.initiateProfile(ProfileData)` 호출.

핵심 테스트:

```java
@Test
@DisplayName("search: tier 필터 부재 — 모든 guest 반환 (page 0, size 50, default sort desc)")
void search_no_tier_filter_returns_all_guests() {
    AdminGuestListQuery query = new AdminGuestListQuery(null, null, null, AdminGuestListQuery.SORT_CREATED_AT_DESC);
    Page<AdminGuestSummaryRow> page = adminGuestQueryRepository.search(query, PageRequest.of(0, 50));

    // 5 seed + V5 super-admin guest 없음 → 정확히 5
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
    AdminGuestListQuery query = new AdminGuestListQuery("FOO", null, null, AdminGuestListQuery.SORT_CREATED_AT_DESC);
    Page<AdminGuestSummaryRow> page = adminGuestQueryRepository.search(query, PageRequest.of(0, 50));

    assertThat(page.getTotalElements()).isEqualTo(2L);
    assertThat(page.getContent()).extracting(AdminGuestSummaryRow::email)
            .allMatch(e -> e.toLowerCase().contains("foo"));
}

@Test
@DisplayName("search: joined_from/joined_to range — inclusive 양쪽")
void search_joined_date_range_inclusive() {
    AdminGuestListQuery query = new AdminGuestListQuery(null,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
            AdminGuestListQuery.SORT_CREATED_AT_DESC);
    Page<AdminGuestSummaryRow> page = adminGuestQueryRepository.search(query, PageRequest.of(0, 50));

    // FOO2(01-15), BAR1(03-10), BAR2(06-20) → 3건
    assertThat(page.getTotalElements()).isEqualTo(3L);
}

@Test
@DisplayName("search: sort last_activity_desc — 활동 0건은 createdAt fallback")
void search_sort_last_activity_desc_with_fallback() {
    AdminGuestListQuery query = new AdminGuestListQuery(null, null, null,
            AdminGuestListQuery.SORT_LAST_ACTIVITY_DESC);
    Page<AdminGuestSummaryRow> page = adminGuestQueryRepository.search(query, PageRequest.of(0, 50));

    // FOO1 활동(2026-04-28 12:00) > BAR3 createdAt(2026-12-25) → BAR3 가 먼저 와야 함
    // 활동 5건 = MAX 가 가장 마지막. FOO1 activity MAX < BAR3 createdAt 이라 BAR3 우선
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
```

`seedGuest` 헬퍼 (Member IT 의 seedMember 와 동형, profile_data 동봉):

```java
private Long seedGuest(long uid, String email, String nickname,
                       String agent, LocalDateTime createdAt) {
    return transactionTemplate.execute(status -> {
        UserAccountData ua = UserAccountData.createForSocial(new UserId(uid), email, ProviderType.GOOGLE);
        userAccountRepository.save(ua);

        GuestData g = GuestData.createForUserAccount(uid, agent);
        g.initiateProfile(ProfileData.create(
                new Nickname(nickname),
                /* introduction= */ null,
                /* 기타 필드 — 기존 ProfileData create signature 참조 */
        ));
        g = guestRepository.save(g);

        // createdAt override (auditing 자동값 위에 native UPDATE)
        entityManager.createNativeQuery("UPDATE user_account SET created_at = :ts WHERE user_id = :uid")
                .setParameter("ts", createdAt)
                .setParameter("uid", uid)
                .executeUpdate();
        return g.getGuestId();
    });
}
```

> 구현자: `ProfileData.create(...)` 정확한 시그니처는 `MemberData.initializeProfile` 사용 사이트 확인 후 동일 호출 패턴. `Nickname` VO + `Bio` 도 Member 와 같은 import.
> cleanup 에서 추가로 `DELETE FROM guest WHERE user_account_id IN (:uids)` 추가.

- [ ] **Step 2: 실패 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminGuestQueryRepositoryImplIT*"`
Expected: FAIL (`AdminGuestQueryRepository` bean 없음 또는 컴파일 에러)

> 위 Task 3·4 가 끝났다면 컴파일 통과 후 fixture/assertion 단계서만 FAIL.

- [ ] **Step 3: 픽스처/단언 정렬**

가능한 경우 first run 으로 PASS 가능 — Member IT 동형이라 fixture 정확성 외 변수 없음. 실패 메시지 확인 후 시드/순서 조정.

- [ ] **Step 4: PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminGuestQueryRepositoryImplIT*"`
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/administration/adapter/out/persistence/impl/AdminGuestQueryRepositoryImplIT.java
git commit -m "test(d8): AdminGuestQueryRepositoryImpl IT — search/findDetail 6 cases (#8)"
```

### Task 6: Service — `AdminGuestQueryService`

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminGuestQueryService.java`

`AdminMemberQueryService` 동형. `RECENT_ACTIVITY_LIMIT=30` 동일.

- [ ] **Step 1: 신규 파일 작성**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestSummaryResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.GuestProfileSummary;
import com.pfplaybackend.api.administration.adapter.in.web.dto.RecentActivityLogItem;
import com.pfplaybackend.api.administration.adapter.in.web.dto.UserAccountSummary;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminGuestQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestDetailRow;
import com.pfplaybackend.api.administration.domain.entity.UserActivityLogData;
import com.pfplaybackend.api.administration.domain.exception.AdminGuestException;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only query service for the admin Guest views.
 *
 * <p>getDetail composes two read sources:
 *  - AdminGuestQueryRepository.findDetail — single QueryDSL row of guest + userAccount.
 *  - UserActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc —
 *    recent 30 audit rows for the linked user_account_id (Member 와 재사용).
 *
 * <p>Cross-BC entity reference (User) is confined to AdminGuestQueryRepositoryImpl;
 * this service operates exclusively on administration BC DTOs.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §7.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminGuestQueryService {

    /** Spec — recentActivityLog 응답 상한. AdminMemberQueryService 와 동일값. */
    static final int RECENT_ACTIVITY_LIMIT = 30;

    private final AdminGuestQueryRepository guestRepository;
    private final UserActivityLogRepository userActivityLogRepository;

    public AdminGuestDetailResponse getDetail(Long guestId) {
        AdminGuestDetailRow row = guestRepository.findDetail(guestId)
                .orElseThrow(() -> ExceptionCreator.create(AdminGuestException.GUEST_NOT_FOUND));

        List<UserActivityLogData> logs = row.userAccountId() == null
                ? List.of()
                : userActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(
                        row.userAccountId());

        List<RecentActivityLogItem> activityItems = logs.stream()
                .map(d -> new RecentActivityLogItem(
                        d.getEventType(), d.getPartyroomId(), d.getMetadata(), d.getOccurredAt()))
                .toList();

        return new AdminGuestDetailResponse(
                row.guestId(),
                new UserAccountSummary(row.userAccountId(), row.email(), row.providerType(),
                        row.lastLoginAt(), row.withdrawnAt()),
                new GuestProfileSummary(row.nickname(), row.introduction()),
                row.agent(),
                row.isProfileUpdated(),
                row.createdAt(),
                row.withdrawnAt() != null,
                row.withdrawnAt(),
                activityItems);
    }

    public Page<AdminGuestSummaryResponse> getList(AdminGuestListQuery query, Pageable pageable) {
        return guestRepository.search(query, pageable)
                .map(r -> new AdminGuestSummaryResponse(
                        r.guestId(),
                        r.userAccountId(),
                        r.email(),
                        r.providerType(),
                        r.nickname(),
                        r.agent(),
                        r.isProfileUpdated(),
                        r.lastLoginAt(),
                        r.createdAt(),
                        r.withdrawnAt() != null,
                        r.withdrawnAt()));
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/application/service/AdminGuestQueryService.java
git commit -m "feat(d8): AdminGuestQueryService — getList/getDetail + activity log 합성 (#8)"
```

### Task 7: Service 단위 테스트 — `AdminGuestQueryServiceTest`

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminGuestQueryServiceTest.java`

`AdminMemberQueryServiceTest` 패턴 동형. Repository / UserActivityLogRepository mock.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.api.administration.application.service;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.out.persistence.AdminGuestQueryRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.UserActivityLogRepository;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestDetailRow;
import com.pfplaybackend.api.administration.adapter.out.persistence.dto.AdminGuestSummaryRow;
import com.pfplaybackend.api.administration.domain.exception.AdminGuestException;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.exception.PfPlayBackendException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AdminGuestQueryServiceTest {

    @Mock AdminGuestQueryRepository guestRepository;
    @Mock UserActivityLogRepository userActivityLogRepository;

    @InjectMocks AdminGuestQueryService service;

    @Test
    @DisplayName("getDetail: 존재하는 guest — DTO 조립, withdrawn flag derive")
    void getDetail_existingGuest_returnsDto() {
        AdminGuestDetailRow row = new AdminGuestDetailRow(
                50L, 100L, "g@x", ProviderType.GOOGLE,
                LocalDateTime.of(2026, 5, 1, 10, 0), null,
                "guestNick", "intro", "ua-string", true,
                LocalDateTime.of(2026, 4, 1, 0, 0));
        given(guestRepository.findDetail(50L)).willReturn(Optional.of(row));
        given(userActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(100L))
                .willReturn(List.of());

        AdminGuestDetailResponse res = service.getDetail(50L);

        assertThat(res.guestId()).isEqualTo(50L);
        assertThat(res.userAccount().email()).isEqualTo("g@x");
        assertThat(res.profile().nickname()).isEqualTo("guestNick");
        assertThat(res.agent()).isEqualTo("ua-string");
        assertThat(res.isProfileUpdated()).isTrue();
        assertThat(res.withdrawn()).isFalse();
        assertThat(res.recentActivityLog()).isEmpty();
    }

    @Test
    @DisplayName("getDetail: 탈퇴 처리된 guest — withdrawn=true")
    void getDetail_withdrawnGuest_setsFlag() {
        AdminGuestDetailRow row = new AdminGuestDetailRow(
                51L, 101L, "withdrawn-101@withdrawn.local", ProviderType.GOOGLE,
                null, LocalDateTime.of(2026, 5, 19, 0, 0),
                null, null, null, false,
                LocalDateTime.of(2026, 5, 1, 0, 0));
        given(guestRepository.findDetail(51L)).willReturn(Optional.of(row));
        given(userActivityLogRepository.findTop30ByUserAccountIdOrderByOccurredAtDescLogIdDesc(101L))
                .willReturn(List.of());

        AdminGuestDetailResponse res = service.getDetail(51L);

        assertThat(res.withdrawn()).isTrue();
        assertThat(res.withdrawnAt()).isEqualTo(LocalDateTime.of(2026, 5, 19, 0, 0));
    }

    @Test
    @DisplayName("getDetail: 미존재 — GUEST_NOT_FOUND throw")
    void getDetail_missingGuest_throws() {
        given(guestRepository.findDetail(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(99L))
                .isInstanceOf(PfPlayBackendException.class)
                .hasMessageContaining("Guest");
    }

    @Test
    @DisplayName("getDetail: userAccountId=null row — activity log 호출 skip + 빈 리스트")
    void getDetail_nullUserAccountId_skipsActivityLookup() {
        AdminGuestDetailRow row = new AdminGuestDetailRow(
                52L, null, null, null, null, null, null, null, null, false,
                LocalDateTime.of(2026, 5, 1, 0, 0));
        given(guestRepository.findDetail(52L)).willReturn(Optional.of(row));

        AdminGuestDetailResponse res = service.getDetail(52L);

        assertThat(res.recentActivityLog()).isEmpty();
        // userActivityLogRepository.findTop30... 호출되지 않았어야 — mock 디폴트 동작이라 자동
    }

    @Test
    @DisplayName("getList: repository search 결과를 Response 로 매핑 + withdrawn derive")
    void getList_mapsRowsToResponses() {
        AdminGuestSummaryRow row = new AdminGuestSummaryRow(
                50L, 100L, "g@x", ProviderType.GOOGLE,
                "nick", "ua", true,
                LocalDateTime.of(2026, 5, 1, 10, 0),
                LocalDateTime.of(2026, 4, 1, 0, 0),
                null);
        Page<AdminGuestSummaryRow> page = new PageImpl<>(List.of(row), PageRequest.of(0, 50), 1L);
        given(guestRepository.search(any(), any(Pageable.class))).willReturn(page);

        AdminGuestListQuery query = new AdminGuestListQuery(null, null, null,
                AdminGuestListQuery.SORT_CREATED_AT_DESC);
        Page<?> result = service.getList(query, PageRequest.of(0, 50));

        assertThat(result.getTotalElements()).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: 실패 → PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminGuestQueryServiceTest*"`
Expected: PASS (5 tests)

- [ ] **Step 3: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/administration/application/service/AdminGuestQueryServiceTest.java
git commit -m "test(d8): AdminGuestQueryService unit test — 5 cases (#8)"
```

### Task 8: Controller — `AdminGuestQueryController`

**Files:**
- Create: `app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminGuestQueryController.java`

`AdminMemberQueryController` 동형. `tier` param 만 빠짐.

- [ ] **Step 1: 신규 파일 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestListQuery;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestSummaryResponse;
import com.pfplaybackend.api.administration.application.service.AdminGuestQueryService;
import com.pfplaybackend.api.administration.domain.exception.AdminGuestException;
import com.pfplaybackend.api.common.ApiCommonResponse;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Guest 어드민 조회 controller (read-only).
 *
 * <p>모든 endpoint 는 {@code @adminAuth.isAdmin()} 로 게이팅. {@code GUEST_NOT_FOUND}
 * 도메인 예외는 GlobalExceptionHandler 가 404 로, {@code INVALID_LIST_QUERY} 는 400.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md §5.
 */
@Tag(name = "Admin Guest Queries API", description = "Guest 목록/상세 read-only")
@RestController
@RequestMapping("/api/v1/admin/guests")
@RequiredArgsConstructor
@Validated
public class AdminGuestQueryController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final String SORT_PATTERN = "created_at_desc|created_at_asc|last_activity_desc";

    private final AdminGuestQueryService adminGuestQueryService;

    @Operation(summary = "Guest 목록 — filter(email/joined_*)/sort/pagination")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping
    public ResponseEntity<ApiCommonResponse<Page<AdminGuestSummaryResponse>>> getList(
            @RequestParam(required = false) @Size(max = 255) String email,
            @RequestParam(name = "joined_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @RequestParam(name = "joined_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = AdminGuestListQuery.SORT_CREATED_AT_DESC)
            @Pattern(regexp = SORT_PATTERN) String sort
    ) {
        if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
            throw ExceptionCreator.create(AdminGuestException.INVALID_LIST_QUERY);
        }

        AdminGuestListQuery query = new AdminGuestListQuery(email, joinedFrom, joinedTo, sort);
        Pageable pageable = PageRequest.of(page, size);
        Page<AdminGuestSummaryResponse> result = adminGuestQueryService.getList(query, pageable);
        return ResponseEntity.ok(ApiCommonResponse.success(result));
    }

    @Operation(summary = "Guest 상세 — recentActivityLog top 30")
    @PreAuthorize("@adminAuth.isAdmin()")
    @GetMapping("/{guestId}")
    public ResponseEntity<ApiCommonResponse<AdminGuestDetailResponse>> getDetail(
            @PathVariable Long guestId) {
        return ResponseEntity.ok(ApiCommonResponse.success(
                adminGuestQueryService.getDetail(guestId)));
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/pfplaybackend/api/administration/adapter/in/web/AdminGuestQueryController.java
git commit -m "feat(d8): AdminGuestQueryController — GET /api/v1/admin/guests[+/{guestId}] (#8)"
```

### Task 9: `AbstractAdminWebMvcTest` 확장

**Files:**
- Modify: `app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java`

`@WebMvcTest` 배열에 `AdminGuestQueryController.class` 추가, `AdminGuestQueryService` MockBean 추가.

- [ ] **Step 1: import + 배열 + MockBean 추가**

```java
import com.pfplaybackend.api.administration.adapter.in.web.AdminGuestQueryController;
import com.pfplaybackend.api.administration.application.service.AdminGuestQueryService;

// @WebMvcTest 배열에 추가:
        AdminGuestQueryController.class,
// MockBean 영역에 추가:
    @MockBean protected AdminGuestQueryService adminGuestQueryService;
```

- [ ] **Step 2: 기존 테스트 회귀 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMember*ControllerTest" --tests "*AdminPartyroom*ControllerTest"`
Expected: PASS (Member/Partyroom 컨트롤러 테스트 무파손)

- [ ] **Step 3: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/admin/adapter/in/web/AbstractAdminWebMvcTest.java
git commit -m "test(d8): AbstractAdminWebMvcTest — AdminGuestQueryController 등록 + service mock (#8)"
```

### Task 10: Controller 테스트 — `AdminGuestQueryControllerTest`

**Files:**
- Test: `app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminGuestQueryControllerTest.java`

`AdminMemberQueryControllerTest` 동형. tier 검증 case 제거, agent/isProfileUpdated 직렬화 검증 추가.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.pfplaybackend.api.administration.adapter.in.web;

import com.pfplaybackend.api.admin.adapter.in.web.AbstractAdminWebMvcTest;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestDetailResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.AdminGuestSummaryResponse;
import com.pfplaybackend.api.administration.adapter.in.web.dto.GuestProfileSummary;
import com.pfplaybackend.api.administration.adapter.in.web.dto.UserAccountSummary;
import com.pfplaybackend.api.administration.domain.exception.AdminGuestException;
import com.pfplaybackend.api.common.config.security.enums.ProviderType;
import com.pfplaybackend.api.common.exception.ExceptionCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminGuestQueryControllerTest extends AbstractAdminWebMvcTest {

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests/{id} 200 — detail 응답 + agent/isProfileUpdated 직렬화")
    void getDetail_admin_returns200WithBody() throws Exception {
        AdminGuestDetailResponse response = new AdminGuestDetailResponse(
                50L,
                new UserAccountSummary(100L, "g@x", ProviderType.GOOGLE,
                        LocalDateTime.of(2026, 5, 1, 10, 0), null),
                new GuestProfileSummary("Nick", "intro"),
                "ua-string",
                true,
                LocalDateTime.of(2026, 4, 1, 0, 0),
                false, null,
                List.of());
        given(adminGuestQueryService.getDetail(50L)).willReturn(response);

        mockMvc.perform(get("/api/v1/admin/guests/50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guestId").value(50))
                .andExpect(jsonPath("$.data.userAccount.userAccountId").value(100))
                .andExpect(jsonPath("$.data.profile.nickname").value("Nick"))
                .andExpect(jsonPath("$.data.agent").value("ua-string"))
                .andExpect(jsonPath("$.data.isProfileUpdated").value(true))
                .andExpect(jsonPath("$.data.withdrawn").value(false))
                .andExpect(jsonPath("$.data.recentActivityLog").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests/{id} 404 — GUEST_NOT_FOUND")
    void getDetail_guestNotFound_returns404() throws Exception {
        willThrow(ExceptionCreator.create(AdminGuestException.GUEST_NOT_FOUND))
                .given(adminGuestQueryService).getDetail(99L);

        mockMvc.perform(get("/api/v1/admin/guests/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /admin/guests/{id} 401 — 미인증")
    void getDetail_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests/50"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("GET /admin/guests/{id} 403 — 인증된 non-admin")
    void getDetail_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests/50"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 200 — 빈 결과")
    void getList_returns_200_empty_content() throws Exception {
        Page<AdminGuestSummaryResponse> emptyPage =
                new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 50), 0L);
        given(adminGuestQueryService.getList(any(), any(Pageable.class))).willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/admin/guests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 200 — content row + agent/isProfileUpdated 직렬화")
    void getList_returns_200_with_content_row() throws Exception {
        AdminGuestSummaryResponse row = new AdminGuestSummaryResponse(
                50L, 100L, "g@x", ProviderType.GOOGLE,
                "Nick", "ua-string", true,
                LocalDateTime.of(2026, 5, 1, 10, 0),
                LocalDateTime.of(2026, 4, 1, 0, 0),
                false, null);
        Page<AdminGuestSummaryResponse> page =
                new PageImpl<>(List.of(row), PageRequest.of(0, 50), 1L);
        given(adminGuestQueryService.getList(any(), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/v1/admin/guests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].guestId").value(50))
                .andExpect(jsonPath("$.data.content[0].nickname").value("Nick"))
                .andExpect(jsonPath("$.data.content[0].agent").value("ua-string"))
                .andExpect(jsonPath("$.data.content[0].isProfileUpdated").value(true))
                .andExpect(jsonPath("$.data.content[0].withdrawn").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 400 — size > 200")
    void getList_returns_400_when_size_exceeds_cap() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests").param("size", "10000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 400 — joined_from > joined_to")
    void getList_returns_400_when_date_range_invalid() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests")
                        .param("joined_from", "2026-12-31")
                        .param("joined_to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /admin/guests 400 — sort 허용 외 값")
    void getList_returns_400_when_sort_invalid() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests").param("sort", "random_xyz"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /admin/guests 401 — 미인증")
    void getList_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    @DisplayName("GET /admin/guests 403 — 인증된 non-admin")
    void getList_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/guests"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: PASS 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminGuestQueryControllerTest*"`
Expected: PASS (11 tests)

- [ ] **Step 3: 회귀 검증 — Member 컨트롤러 테스트 + ArchUnit 무파손**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test --tests "*AdminMember*" --tests "*ArchUnit*"`
Expected: PASS (Member 무수정 회귀 가드)

- [ ] **Step 4: 커밋**

```bash
git add app/src/test/java/com/pfplaybackend/api/administration/adapter/in/web/AdminGuestQueryControllerTest.java
git commit -m "test(d8): AdminGuestQueryController WebMvcTest — 11 cases (#8)"
```

### Task 11: Chunk 1 backend 전체 회귀 검증

- [ ] **Step 1: 전체 backend 테스트 실행**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:test`
Expected: PASS (기존 + 신규 모두 GREEN)

- [ ] **Step 2: ArchUnit 룰 확인**

기존 administration BC ArchUnit 룰이 신규 `AdminGuestQueryRepositoryImpl` 에 자동 적용되는지 확인. 별도 룰 추가 불필요.

- [ ] **Step 3: 통합 커밋 (선택) — 단일 PR 시 squash 권장 ([[main_squash_merge]])**

backend chunk 자체는 micro-commit 들로 충분. PR 머지 시 사용자가 squash 결정.

---

## Chunk 2: Frontend — entities/guest + features/guests slices

> pfplay-admin 레포로 이동. backend PR 머지 (또는 적어도 spec response shape 확정) 후 작업 권장하나, type 정의는 spec 만 보고 충분히 가능. **review 검증포인트**: ① 기존 `features/members` 무수정, ② `entities/member` 무수정 + 재사용 ProviderType / UserAccountSummary / RecentActivityLogItem 만 import, ③ tier filter 부재.

### Task 12: pfplay-admin 브랜치 생성

- [ ] **Step 1: pfplay-admin 디렉토리로 이동, 최신 develop pull, 브랜치 생성**

```bash
cd "/c/Users/Eisen/Desktop/Labs/[projects] pfplay/pfplay-admin"
git checkout develop
git pull --ff-only
git checkout -b feature/d8-admin-guest-readonly
```

### Task 13: `entities/guest` 슬라이스 — types

**Files:**
- Create: `src/entities/guest/index.ts`
- Create: `src/entities/guest/model/types.ts`

기존 `entities/member` 의 `ProviderType` / `UserAccountSummary` / `RecentActivityLogItem` 재사용 (admin 콘솔 내부 공용 타입). Member entity 무수정.

- [ ] **Step 1: types.ts 작성**

```typescript
// src/entities/guest/model/types.ts
import type {
  ProviderType,
  UserAccountSummary,
  RecentActivityLogItem,
} from "@/entities/member/model/types"

export interface AdminGuestSummary {
  guestId: number
  userAccountId: number
  email: string
  providerType: ProviderType
  nickname: string | null
  agent: string | null
  isProfileUpdated: boolean
  lastLoginAt: string | null // LocalDateTime ISO string
  createdAt: string
  withdrawn: boolean
  withdrawnAt: string | null
}

export interface GuestProfileSummary {
  nickname: string | null
  introduction: string | null
}

export interface AdminGuestDetail {
  guestId: number
  userAccount: UserAccountSummary
  profile: GuestProfileSummary
  agent: string | null
  isProfileUpdated: boolean
  createdAt: string
  withdrawn: boolean
  withdrawnAt: string | null
  recentActivityLog: RecentActivityLogItem[]
}
```

- [ ] **Step 2: index.ts barrel 작성**

```typescript
// src/entities/guest/index.ts
export type {
  AdminGuestSummary,
  AdminGuestDetail,
  GuestProfileSummary,
} from "./model/types"
```

- [ ] **Step 3: 타입체크**

Run: `yarn tsc --noEmit`
Expected: 통과

- [ ] **Step 4: 커밋**

```bash
git add src/entities/guest/
git commit -m "feat(d8): entities/guest 슬라이스 — AdminGuestSummary/Detail/Profile types (#8)"
```

### Task 14: `features/guests/model/filter-schema.ts` + 테스트

**Files:**
- Create: `src/features/guests/model/filter-schema.ts`
- Create: `src/features/guests/model/__tests__/filter-schema.test.ts`

`features/members/model/filter-schema.ts` 패턴 동형. tier 필드 없음.

- [ ] **Step 1: 기존 members filter-schema 확인 후 동형 작성**

먼저 기존 schema 읽고 패턴 파악:
```bash
cat src/features/members/model/filter-schema.ts
```

- [ ] **Step 2: `filter-schema.ts` 작성** (zod 또는 동등 검증 라이브러리 기존 패턴 따름. members 와 정확히 동형, `tier` 필드 제거):

```typescript
// src/features/guests/model/filter-schema.ts
import { z } from "zod"

export const guestSortValues = [
  "created_at_desc",
  "created_at_asc",
  "last_activity_desc",
] as const

export type GuestSort = (typeof guestSortValues)[number]

export const guestsListQuerySchema = z.object({
  email: z.string().max(255).optional(),
  joined_from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  joined_to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  sort: z.enum(guestSortValues).default("created_at_desc"),
  page: z.coerce.number().int().min(0).default(0),
  size: z.coerce.number().int().min(1).max(200).default(50),
})

export type GuestsListQuery = z.infer<typeof guestsListQuerySchema>
```

> 구현자: members `filter-schema.ts` 의 실제 import (zod vs valibot vs 자체) 와 export 형식에 정확히 맞춰 작성. 위 코드는 zod 가정이며 실제 도구는 기존 파일을 따른다.

- [ ] **Step 3: schema 테스트 작성**

```typescript
// src/features/guests/model/__tests__/filter-schema.test.ts
import { describe, it, expect } from "vitest"
import { guestsListQuerySchema } from "../filter-schema"

describe("guestsListQuerySchema", () => {
  it("defaults sort=created_at_desc / page=0 / size=50", () => {
    const parsed = guestsListQuerySchema.parse({})
    expect(parsed.sort).toBe("created_at_desc")
    expect(parsed.page).toBe(0)
    expect(parsed.size).toBe(50)
  })

  it("rejects size > 200", () => {
    expect(() => guestsListQuerySchema.parse({ size: 500 })).toThrow()
  })

  it("rejects invalid sort", () => {
    expect(() => guestsListQuerySchema.parse({ sort: "random_xyz" })).toThrow()
  })

  it("accepts ISO date format for joined_from/to", () => {
    const parsed = guestsListQuerySchema.parse({
      joined_from: "2026-01-01",
      joined_to: "2026-12-31",
    })
    expect(parsed.joined_from).toBe("2026-01-01")
  })

  it("does not have tier field", () => {
    const parsed = guestsListQuerySchema.parse({ tier: "FM" } as unknown as Record<string, unknown>)
    expect("tier" in parsed).toBe(false)
  })
})
```

- [ ] **Step 4: 테스트 PASS 확인**

Run: `yarn vitest run src/features/guests/model/__tests__/filter-schema.test.ts`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/features/guests/model/
git commit -m "feat(d8): features/guests filter-schema — tier 필드 부재, sort/page/size 동형 (#8)"
```

### Task 15: `features/guests/api/guests-api.ts` + 테스트

**Files:**
- Create: `src/features/guests/api/guests-api.ts`
- Create: `src/features/guests/api/__tests__/guests-api.test.ts`

기존 `members-api.ts` 동형 (listGuests, getGuestDetail).

- [ ] **Step 1: `guests-api.ts` 작성**

```typescript
// src/features/guests/api/guests-api.ts
import { http } from "@/shared/api/http"
import { unwrap } from "@/shared/api/page"
import type { ApiCommonResponse, Page } from "@/shared/api/page"
import { serializeQuery } from "@/shared/lib/url-state"
import type { AdminGuestSummary, AdminGuestDetail } from "@/entities/guest"
import type { GuestsListQuery } from "../model/filter-schema"

export async function listGuests(query: GuestsListQuery): Promise<Page<AdminGuestSummary>> {
  const qs = serializeQuery(query as Record<string, unknown>).toString()
  const res = await http<ApiCommonResponse<Page<AdminGuestSummary>>>(
    `/api/v1/admin/guests${qs ? `?${qs}` : ""}`,
  )
  return unwrap(res)
}

export async function getGuestDetail(guestId: number): Promise<AdminGuestDetail> {
  const res = await http<ApiCommonResponse<AdminGuestDetail>>(
    `/api/v1/admin/guests/${guestId}`,
  )
  return unwrap(res)
}
```

- [ ] **Step 2: 테스트 작성** (기존 `members-api.test.ts` 패턴 동형 — http mock + querystring 검증)

```typescript
// src/features/guests/api/__tests__/guests-api.test.ts
import { describe, it, expect, vi, beforeEach } from "vitest"
import { listGuests, getGuestDetail } from "../guests-api"

vi.mock("@/shared/api/http", () => ({
  http: vi.fn(),
}))

import { http } from "@/shared/api/http"

const httpMock = http as ReturnType<typeof vi.fn>

beforeEach(() => {
  httpMock.mockReset()
})

describe("listGuests", () => {
  it("calls GET /api/v1/admin/guests with serialized query", async () => {
    httpMock.mockResolvedValueOnce({
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, empty: true },
    })

    await listGuests({
      email: "foo",
      joined_from: "2026-01-01",
      joined_to: undefined,
      sort: "created_at_desc",
      page: 0,
      size: 50,
    })

    expect(httpMock).toHaveBeenCalledTimes(1)
    const url = httpMock.mock.calls[0][0] as string
    expect(url).toContain("/api/v1/admin/guests?")
    expect(url).toContain("email=foo")
    expect(url).toContain("joined_from=2026-01-01")
    expect(url).not.toContain("joined_to=")
  })

  it("omits querystring when no params", async () => {
    httpMock.mockResolvedValueOnce({
      data: { content: [], totalElements: 0, totalPages: 0, number: 0, empty: true },
    })

    await listGuests({ sort: "created_at_desc", page: 0, size: 50 })

    const url = httpMock.mock.calls[0][0] as string
    // sort/page/size 는 default 라 serializeQuery 정책에 따라 — members-api 패턴 동일
    expect(url.startsWith("/api/v1/admin/guests")).toBe(true)
  })
})

describe("getGuestDetail", () => {
  it("calls GET /api/v1/admin/guests/{id}", async () => {
    httpMock.mockResolvedValueOnce({
      data: { guestId: 50 /* ... */ },
    })

    await getGuestDetail(50)

    expect(httpMock).toHaveBeenCalledWith("/api/v1/admin/guests/50")
  })
})
```

- [ ] **Step 3: 테스트 PASS 확인**

Run: `yarn vitest run src/features/guests/api/__tests__/guests-api.test.ts`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add src/features/guests/api/guests-api.ts src/features/guests/api/__tests__/guests-api.test.ts
git commit -m "feat(d8): features/guests api — listGuests/getGuestDetail + tests (#8)"
```

### Task 16: React Query hooks — `use-guests-list.ts`, `use-guest-detail.ts` + 테스트

**Files:**
- Create: `src/features/guests/api/use-guests-list.ts`
- Create: `src/features/guests/api/use-guest-detail.ts`
- Create: `src/features/guests/api/__tests__/use-guests-list.test.tsx`
- Create: `src/features/guests/api/__tests__/use-guest-detail.test.tsx`

기존 `use-members-list.ts`, `use-member-detail.ts` 동형.

- [ ] **Step 1: `use-guests-list.ts` 작성**

```typescript
// src/features/guests/api/use-guests-list.ts
import { useQuery } from "@tanstack/react-query"
import { listGuests } from "./guests-api"
import type { GuestsListQuery } from "../model/filter-schema"

export function useGuestsList(query: GuestsListQuery) {
  return useQuery({
    queryKey: ["admin", "guests", query],
    queryFn: () => listGuests(query),
    staleTime: 30_000,
  })
}
```

- [ ] **Step 2: `use-guest-detail.ts` 작성**

```typescript
// src/features/guests/api/use-guest-detail.ts
import { useQuery } from "@tanstack/react-query"
import { getGuestDetail } from "./guests-api"

export function useGuestDetail(guestId: number) {
  return useQuery({
    queryKey: ["admin", "guests", guestId, "detail"],
    queryFn: () => getGuestDetail(guestId),
    staleTime: 30_000,
  })
}
```

- [ ] **Step 3: hooks 테스트** — 기존 `use-members-list.test.tsx` 패턴 동형 (QueryClientProvider wrapping, msw 또는 vi mock 사용 — 기존 컨벤션 따름)

- [ ] **Step 4: 테스트 PASS 확인**

Run: `yarn vitest run src/features/guests/api/__tests__/use-guests-list.test.tsx src/features/guests/api/__tests__/use-guest-detail.test.tsx`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/features/guests/api/use-guests-list.ts src/features/guests/api/use-guest-detail.ts \
        src/features/guests/api/__tests__/use-guests-list.test.tsx \
        src/features/guests/api/__tests__/use-guest-detail.test.tsx
git commit -m "feat(d8): features/guests hooks — useGuestsList/useGuestDetail + tests (#8)"
```

### Task 17: UI 컴포넌트 — `guests-filter-form.tsx` + 테스트

**Files:**
- Create: `src/features/guests/ui/guests-filter-form.tsx`
- Create: `src/features/guests/ui/__tests__/guests-filter-form.test.tsx`

기존 `members-filter-form.tsx` 동형. **tier `<Select>` 블럭 제거** — 이메일 + 가입일 from/to + 정렬만.

- [ ] **Step 1: 컴포넌트 작성**

기존 `members-filter-form.tsx` 의 tier 관련 라인(`tierId`, `TIER_OPTIONS`, tier `<Select>` div) 전부 제거하고 `MembersListQuery` → `GuestsListQuery` 치환. 정렬 옵션은 동일 (`SORT_OPTIONS` 동형 — guest 도 last_activity_desc 지원).

```typescript
// src/features/guests/ui/guests-filter-form.tsx
import { useEffect, useId, useState } from "react"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { useDebounce } from "@/shared/lib/use-debounce"
import type { GuestsListQuery, GuestSort } from "../model/filter-schema"

interface Props {
  query: GuestsListQuery
  onChange: (next: Partial<GuestsListQuery>) => void
  onReset: () => void
}

const SORT_OPTIONS: { value: GuestSort; label: string }[] = [
  { value: "created_at_desc", label: "가입일 ↓" },
  { value: "created_at_asc", label: "가입일 ↑" },
  { value: "last_activity_desc", label: "마지막 활동 ↓" },
]

export function GuestsFilterForm({ query, onChange, onReset }: Props) {
  const emailId = useId()
  const fromId = useId()
  const toId = useId()
  const sortId = useId()

  const [emailDraft, setEmailDraft] = useState(query.email ?? "")
  useEffect(() => { setEmailDraft(query.email ?? "") }, [query.email])
  const debouncedEmail = useDebounce(emailDraft, 300)

  useEffect(() => {
    if (debouncedEmail !== (query.email ?? "")) {
      onChange({ email: debouncedEmail || undefined, page: 0 })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedEmail])

  return (
    <div className="flex flex-wrap gap-2 items-end mb-4">
      <div>
        <label htmlFor={emailId} className="block text-xs font-medium mb-1">이메일</label>
        <Input id={emailId} value={emailDraft} onChange={(e) => setEmailDraft(e.target.value)}
               placeholder="부분일치" className="w-48" />
      </div>
      <div>
        <label htmlFor={fromId} className="block text-xs font-medium mb-1">가입일 from</label>
        <Input id={fromId} type="date" value={query.joined_from ?? ""}
               onChange={(e) => onChange({ joined_from: e.target.value || undefined, page: 0 })}
               className="w-36" />
      </div>
      <div>
        <label htmlFor={toId} className="block text-xs font-medium mb-1">가입일 to</label>
        <Input id={toId} type="date" value={query.joined_to ?? ""}
               onChange={(e) => onChange({ joined_to: e.target.value || undefined, page: 0 })}
               className="w-36" />
      </div>
      <div>
        <label htmlFor={sortId} className="block text-xs font-medium mb-1">정렬</label>
        <Select value={query.sort} onValueChange={(v) => onChange({ sort: v as GuestSort, page: 0 })}>
          <SelectTrigger id={sortId} className="w-44" aria-label="정렬"><SelectValue /></SelectTrigger>
          <SelectContent>
            {SORT_OPTIONS.map((o) => <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      <Button variant="outline" onClick={onReset}>초기화</Button>
    </div>
  )
}
```

- [ ] **Step 2: 테스트 — tier filter 부재 검증 포함**

```typescript
// src/features/guests/ui/__tests__/guests-filter-form.test.tsx
import { describe, it, expect } from "vitest"
import { render, screen } from "@testing-library/react"
import { GuestsFilterForm } from "../guests-filter-form"

describe("GuestsFilterForm", () => {
  const baseQuery = { sort: "created_at_desc", page: 0, size: 50 } as const

  it("does NOT render tier select (Decision #4 — Guest 탭 tier filter 부재)", () => {
    render(<GuestsFilterForm query={baseQuery} onChange={() => {}} onReset={() => {}} />)
    expect(screen.queryByLabelText("권한")).not.toBeInTheDocument()
  })

  it("renders email / joined_from / joined_to / 정렬 inputs", () => {
    render(<GuestsFilterForm query={baseQuery} onChange={() => {}} onReset={() => {}} />)
    expect(screen.getByLabelText("이메일")).toBeInTheDocument()
    expect(screen.getByLabelText("가입일 from")).toBeInTheDocument()
    expect(screen.getByLabelText("가입일 to")).toBeInTheDocument()
    expect(screen.getByLabelText("정렬")).toBeInTheDocument()
  })

  // 추가: debounce email onChange, date onChange, sort onChange — members-filter-form.test.tsx 동형
})
```

- [ ] **Step 3: 테스트 PASS 확인**

Run: `yarn vitest run src/features/guests/ui/__tests__/guests-filter-form.test.tsx`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add src/features/guests/ui/guests-filter-form.tsx src/features/guests/ui/__tests__/guests-filter-form.test.tsx
git commit -m "feat(d8): GuestsFilterForm — tier filter 부재 + tests (#8)"
```

### Task 18: UI 컴포넌트 — `guests-table.tsx` + 테스트

**Files:**
- Create: `src/features/guests/ui/guests-table.tsx`
- Create: `src/features/guests/ui/__tests__/guests-table.test.tsx`

기존 `members-table.tsx` 동형. tier 컬럼 제거, agent 컬럼 추가, 클릭 시 `/guests/{guestId}` 로 navigate.

- [ ] **Step 1: 컴포넌트 작성**

```typescript
// src/features/guests/ui/guests-table.tsx
import { useNavigate } from "react-router-dom"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Badge } from "@/components/ui/badge"
import { Skeleton } from "@/components/ui/skeleton"
import type { AdminGuestSummary } from "@/entities/guest"
import { formatKst } from "@/shared/lib/format-kst"

interface Props {
  rows: AdminGuestSummary[]
  isLoading: boolean
  isEmpty: boolean
}

export function GuestsTable({ rows, isLoading, isEmpty }: Props) {
  const navigate = useNavigate()
  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 5 }).map((_, i) => (<Skeleton key={i} className="h-12 w-full" />))}
      </div>
    )
  }
  if (isEmpty) {
    return <div className="text-center py-12 text-muted-foreground">조건에 맞는 게스트가 없습니다</div>
  }
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>ID</TableHead>
          <TableHead>이메일</TableHead>
          <TableHead>가입 경로</TableHead>
          <TableHead>닉네임</TableHead>
          <TableHead>agent</TableHead>
          <TableHead>마지막 로그인</TableHead>
          <TableHead>가입일</TableHead>
          <TableHead>상태</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.guestId} className="cursor-pointer hover:bg-accent/50"
                    onClick={() => navigate(`/guests/${row.guestId}`)}>
            <TableCell>{row.guestId}</TableCell>
            <TableCell>{row.email}</TableCell>
            <TableCell>{row.providerType}</TableCell>
            <TableCell>{row.nickname ?? "-"}</TableCell>
            <TableCell className="max-w-[16rem] truncate" title={row.agent ?? ""}>{row.agent ?? "-"}</TableCell>
            <TableCell>{formatKst(row.lastLoginAt)}</TableCell>
            <TableCell>{formatKst(row.createdAt)}</TableCell>
            <TableCell>
              {row.withdrawn
                ? <Badge variant="muted" title={row.withdrawnAt ?? ""}>탈퇴됨</Badge>
                : <Badge variant="outline">활동 중</Badge>}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
```

- [ ] **Step 2: 테스트 작성** (members-table.test.tsx 동형 — render rows / empty / loading / row click navigate)

- [ ] **Step 3: 테스트 PASS 확인**

Run: `yarn vitest run src/features/guests/ui/__tests__/guests-table.test.tsx`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add src/features/guests/ui/guests-table.tsx src/features/guests/ui/__tests__/guests-table.test.tsx
git commit -m "feat(d8): GuestsTable — tier 컬럼 부재 + agent 컬럼 + click navigate (#8)"
```

### Task 19: UI 컴포넌트 — `guest-detail-cards.tsx` + 테스트

**Files:**
- Create: `src/features/guests/ui/guest-detail-cards.tsx`
- Create: `src/features/guests/ui/__tests__/guest-detail-cards.test.tsx`

기존 `member-detail-cards.tsx` 동형. mutation 영역 미렌더 (read-only invariant).

- [ ] **Step 1: 컴포넌트 작성** (member-detail-cards 의 mutation dropdown 호출 부 제거, wallet/tier 카드는 agent/isProfileUpdated 로 대체. UserAccount 카드 + RecentActivityLog 영역은 동일 패턴)

- [ ] **Step 2: 테스트 — mutation dropdown 부재 검증 포함**

```typescript
// src/features/guests/ui/__tests__/guest-detail-cards.test.tsx
describe("GuestDetailCards", () => {
  it("does NOT render mutation actions dropdown (read-only invariant)", () => {
    render(<GuestDetailCards detail={fixtureDetail} />)
    expect(screen.queryByRole("button", { name: /작업/i })).not.toBeInTheDocument()
    expect(screen.queryByText("등급 변경")).not.toBeInTheDocument()
    expect(screen.queryByText("비식별화 탈퇴")).not.toBeInTheDocument()
  })

  it("renders agent + isProfileUpdated fields", () => {
    render(<GuestDetailCards detail={fixtureDetail} />)
    expect(screen.getByText(/ua-string/)).toBeInTheDocument()
  })
})
```

- [ ] **Step 3: 테스트 PASS 확인**

Run: `yarn vitest run src/features/guests/ui/__tests__/guest-detail-cards.test.tsx`

- [ ] **Step 4: 커밋**

```bash
git add src/features/guests/ui/guest-detail-cards.tsx src/features/guests/ui/__tests__/guest-detail-cards.test.tsx
git commit -m "feat(d8): GuestDetailCards — mutation dropdown 부재 + agent/isProfileUpdated (#8)"
```

### Task 20: Chunk 2 frontend 슬라이스 전체 회귀 검증

- [ ] **Step 1: 전체 unit test**

Run: `yarn vitest run`
Expected: PASS (기존 + 신규)

- [ ] **Step 2: 타입체크 + 린트**

Run: `yarn tsc --noEmit && yarn lint`
Expected: 0 error

---

## Chunk 3: Frontend — widgets/guests-list + pages/members-page Tabs 컨테이너 + 회귀

> 슬라이스를 통합해 사용자에게 보이는 화면 완성. **review 검증포인트**: ① MEMBER 탭 동작 무변경(회귀 가드 테스트), ② URL `?tab=*` ↔ active tab sync, ③ 두 탭 동시 마운트되지 않음 (TabsContent 의 force-mount false 동작), ④ 라우트 `/guests/{guestId}` 등록.

### Task 21: `widgets/guests-list.tsx` 신설

**Files:**
- Create: `src/widgets/guests-list.tsx`

기존 `widgets/members-list.tsx` 패턴 동형 (`MembersListWidget` → `GuestsListWidget` 치환). useUrlQueryState 의 schema 분리 — guest 전용 schema 사용.

- [ ] **Step 1: 컴포넌트 작성** (members-list.tsx 거의 그대로 — `guestsListQuerySchema`, `useGuestsList`, `GuestsFilterForm`, `GuestsTable` 로 치환. 헤더 텍스트 "회원" → "게스트")

```typescript
// src/widgets/guests-list.tsx
import { guestsListQuerySchema, type GuestsListQuery } from "@/features/guests/model/filter-schema"
import { useGuestsList } from "@/features/guests/api/use-guests-list"
import { GuestsFilterForm } from "@/features/guests/ui/guests-filter-form"
import { GuestsTable } from "@/features/guests/ui/guests-table"
import { useUrlQueryState } from "@/shared/lib/use-url-query-state"
import { Pagination } from "@/widgets/pagination"
import { ApiError } from "@/shared/api/error"

export function GuestsListWidget() {
  const { query, setQuery, reset } = useUrlQueryState(guestsListQuerySchema)
  if (query === null) return null
  return <GuestsListContent query={query} setQuery={setQuery} reset={reset} />
}

interface ContentProps {
  query: GuestsListQuery
  setQuery: (next: Partial<GuestsListQuery>) => void
  reset: () => void
}

function GuestsListContent({ query, setQuery, reset }: ContentProps) {
  const { data, isLoading, error } = useGuestsList(query)
  const goToPage = (page: number) => setQuery({ page })

  return (
    <div className="p-6 lg:p-8">
      <div className="mb-6 flex items-center justify-between">
        <h2 className="text-2xl font-bold">게스트</h2>
        {data && <p className="text-sm text-muted-foreground">총 {data.totalElements}건</p>}
      </div>
      <GuestsFilterForm query={query} onChange={setQuery} onReset={reset} />
      {error instanceof ApiError && error.status === 403 && (
        <p className="text-destructive text-sm mb-2">이 화면을 볼 권한이 없습니다</p>
      )}
      <GuestsTable rows={data?.content ?? []} isLoading={isLoading}
                   isEmpty={!isLoading && (data?.empty ?? false)} />
      {data && (
        <Pagination page={data.number} totalPages={data.totalPages}
                    totalElements={data.totalElements} onChange={goToPage} />
      )}
    </div>
  )
}
```

- [ ] **Step 2: 타입체크 통과 확인**

Run: `yarn tsc --noEmit`

- [ ] **Step 3: 커밋**

```bash
git add src/widgets/guests-list.tsx
git commit -m "feat(d8): widgets/guests-list — MembersListWidget 패턴 동형 (#8)"
```

### Task 22: `pages/members-page.tsx` Tabs 컨테이너 전환 + 테스트

**Files:**
- Modify: `src/pages/members-page.tsx` (5줄 → ~30줄)
- Create / Modify: `src/pages/__tests__/members-page.test.tsx`

기존 5줄 wrapper 를 Tabs 컨테이너로 전환. URL search param `tab` 으로 active sync.

- [ ] **Step 1: `members-page.tsx` 수정**

```typescript
// src/pages/members-page.tsx
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useSearchParams } from "react-router-dom"
import { MembersListWidget } from "@/widgets/members-list"
import { GuestsListWidget } from "@/widgets/guests-list"

const VALID_TABS = ["member", "guest"] as const
type Tab = (typeof VALID_TABS)[number]
const DEFAULT_TAB: Tab = "member"

function parseTab(raw: string | null): Tab {
  return raw && (VALID_TABS as readonly string[]).includes(raw) ? (raw as Tab) : DEFAULT_TAB
}

export function MembersPage() {
  const [params, setParams] = useSearchParams()
  const tab = parseTab(params.get("tab"))

  const handleChange = (next: string) => {
    const nextParams = new URLSearchParams(params)
    nextParams.set("tab", next)
    setParams(nextParams, { replace: false })
  }

  return (
    <Tabs value={tab} onValueChange={handleChange} className="p-6 lg:p-8">
      <TabsList>
        <TabsTrigger value="member">정회원</TabsTrigger>
        <TabsTrigger value="guest">GUEST</TabsTrigger>
      </TabsList>
      <TabsContent value="member"><MembersListWidget /></TabsContent>
      <TabsContent value="guest"><GuestsListWidget /></TabsContent>
    </Tabs>
  )
}
```

> 구현자: `shadcn/ui` `Tabs` 기본 동작은 inactive TabsContent 를 unmount (force-mount 미설정 시) — 두 탭 동시 마운트 방지 자동 보장. 만약 빌드에서 다르게 동작한다면 `forceMount={undefined}` 명시.

- [ ] **Step 2: 테스트 작성** (`pages/__tests__/members-page.test.tsx`)

```typescript
// src/pages/__tests__/members-page.test.tsx
import { describe, it, expect, vi } from "vitest"
import { render, screen, fireEvent } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { MembersPage } from "../members-page"

vi.mock("@/widgets/members-list", () => ({
  MembersListWidget: () => <div data-testid="members-widget">MEMBER_WIDGET</div>,
}))
vi.mock("@/widgets/guests-list", () => ({
  GuestsListWidget: () => <div data-testid="guests-widget">GUEST_WIDGET</div>,
}))

function renderWithRoute(initialPath: string) {
  const client = new QueryClient()
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/members" element={<MembersPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe("MembersPage Tabs container", () => {
  it("defaults to MEMBER tab when no ?tab param", () => {
    renderWithRoute("/members")
    expect(screen.getByTestId("members-widget")).toBeInTheDocument()
    expect(screen.queryByTestId("guests-widget")).not.toBeInTheDocument()
  })

  it("renders GUEST tab when ?tab=guest", () => {
    renderWithRoute("/members?tab=guest")
    expect(screen.getByTestId("guests-widget")).toBeInTheDocument()
    expect(screen.queryByTestId("members-widget")).not.toBeInTheDocument()
  })

  it("renders MEMBER tab for invalid ?tab values (fallback)", () => {
    renderWithRoute("/members?tab=invalid_xyz")
    expect(screen.getByTestId("members-widget")).toBeInTheDocument()
  })

  it("changes URL to ?tab=guest when GUEST trigger clicked", () => {
    renderWithRoute("/members")
    fireEvent.click(screen.getByText("GUEST"))
    // useSearchParams 의 변경은 동기 — Tabs 내부 콘텐츠 swap 확인
    expect(screen.getByTestId("guests-widget")).toBeInTheDocument()
  })

  it("회귀 가드: MEMBER 탭 기본 렌더 (Member widget 호출됨)", () => {
    renderWithRoute("/members")
    expect(screen.getByTestId("members-widget")).toBeInTheDocument()
  })
})
```

- [ ] **Step 3: 테스트 PASS 확인**

Run: `yarn vitest run src/pages/__tests__/members-page.test.tsx`
Expected: PASS (5 tests)

- [ ] **Step 4: 커밋**

```bash
git add src/pages/members-page.tsx src/pages/__tests__/members-page.test.tsx
git commit -m "feat(d8): members-page Tabs 컨테이너 — 정회원/GUEST + URL sync (#8)"
```

### Task 23: `pages/guest-detail-page.tsx` 신설 + 테스트

**Files:**
- Create: `src/pages/guest-detail-page.tsx`
- Create: `src/pages/__tests__/guest-detail-page.test.tsx`

기존 `pages/member-detail-page.tsx` 동형. `useParams` 로 `guestId` 추출, `useGuestDetail` 호출.

- [ ] **Step 1: 컴포넌트 작성** (member-detail-page 동형 — useParams `:guestId` 파싱, `useGuestDetail`, loading/error/notFound 분기 + `GuestDetailCards` 렌더)

- [ ] **Step 2: 테스트 작성** (member-detail-page.test.tsx 동형 — 라우트 param → hook → GuestDetailCards 통합)

- [ ] **Step 3: 테스트 PASS 확인**

Run: `yarn vitest run src/pages/__tests__/guest-detail-page.test.tsx`

- [ ] **Step 4: 커밋**

```bash
git add src/pages/guest-detail-page.tsx src/pages/__tests__/guest-detail-page.test.tsx
git commit -m "feat(d8): guest-detail-page — GuestDetailCards 래핑 + tests (#8)"
```

### Task 24: 라우트 등록 — `/guests/:guestId`

**Files:**
- Modify: `src/app/routes.tsx` (또는 라우트 등록 파일 — 기존 `member-detail-page` 라우트 인접 위치)

- [ ] **Step 1: 라우트 등록**

기존 `<Route path="/members/:memberId" element={<MemberDetailPage />} />` 패턴 인접에 `<Route path="/guests/:guestId" element={<GuestDetailPage />} />` 추가.

> 구현자: 라우트 등록 파일 정확한 위치 확인 후 수정. `src/app/router.tsx`, `src/app/routes.tsx`, `src/main.tsx` 등 후보. import 추가.

- [ ] **Step 2: e2e smoke (수동) 또는 라우트 등록 테스트**

라우트 파일이 단위 테스트되고 있다면 거기에 case 추가. 아니면 수동 smoke.

- [ ] **Step 3: 커밋**

```bash
git add src/app/...
git commit -m "feat(d8): /guests/:guestId 라우트 등록 (#8)"
```

### Task 25: 전체 회귀 검증 + 통합 커밋

- [ ] **Step 1: 전체 unit test + 타입체크 + 린트**

Run:
```bash
yarn vitest run
yarn tsc --noEmit
yarn lint
```
Expected: PASS / 0 error

- [ ] **Step 2: dev 서버 실행, 수동 smoke**

Run: `yarn dev`
- `/members` → 정회원 탭 default, 기존 동작 그대로
- `/members?tab=guest` → GUEST 탭, list 정상 (backend mock 응답 또는 실제 stg 연동 시 검증)
- GUEST row click → `/guests/{guestId}` navigate
- 상세 페이지: agent / isProfileUpdated 노출, mutation dropdown 부재

- [ ] **Step 3: 회귀 가드 — Member 탭 동작 완전 동일 확인**

기존 사용 사이클: 이메일 검색 / 권한 필터 (FM/AM/GT) / 가입일 범위 / 정렬 / 페이징 / row click → MemberDetailPage / mutation dropdown 가용 — 모두 무파손.

- [ ] **Step 4: pfplay-admin PR 생성**

```bash
git push -u origin feature/d8-admin-guest-readonly
gh pr create --title "D/#8 어드민 GUEST read-only 조회 (frontend)" --body "$(cat <<'EOF'
## Summary
- 어드민 콘솔에 GUEST 사용자 read-only view 추가 — `/members` 페이지에 [정회원|GUEST] 탭 분리
- 신규: `entities/guest`, `features/guests` (api/model/ui), `widgets/guests-list`, `pages/guest-detail-page`
- 수정: `pages/members-page.tsx` (Tabs 컨테이너로 전환)
- MEMBER 코드 무수정 — 회귀 zero 가드 테스트 포함

Spec: pfplay-platform `docs/superpowers/specs/2026-05-20-d8-admin-guest-readonly-design.md`
Plan: pfplay-platform `docs/superpowers/plans/2026-05-20-d8-admin-guest-readonly.md`
Backend PR: pfplay-platform PR (TBD, 먼저 머지되어야 함 — Spec §13 contract sync)

## Test plan
- [x] vitest run — 전체 GREEN
- [x] tsc --noEmit — 0 error
- [x] lint — 0 error
- [x] members-page 탭 컨테이너 회귀 가드 테스트
- [x] guests-filter-form tier filter 부재 검증
- [x] guest-detail-cards mutation dropdown 부재 검증
- [ ] stg 배포 후 GUEST 데이터 실연동 smoke

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## 최종 통합 검증 (PR 머지 전)

- [ ] backend PR (pfplay-platform `feature/d8-admin-guest-readonly`) → develop 머지 → stg 자동 배포 → 헬스 체크
- [ ] frontend PR (pfplay-admin `feature/d8-admin-guest-readonly`) → develop 머지 (backend stg 안정화 확인 후) → admin stg 자동 배포
- [ ] 사용자 stg 검수: GUEST 데이터 노출, 필터/정렬/페이징, 상세, MEMBER 탭 무영향
- [ ] prod 승격은 별도 release 게이트 = 사용자 영역

---

## 관련 메모리

- [[feedback_pr_series_workflow]] — chunk + atomic group 패턴 (본 plan 의 3-chunk 구성 근거)
- [[feedback_commit_consolidation_before_push]] — push/PR 직전 squash 결정 사용자에게 위임
- [[feedback_korean_issue_commit_pr]] — 이슈/커밋/PR 한글
- [[reference_pfplay_platform_jdk]] — Gradle 호출 JDK 환경 변수 필수
- [[feedback_autonomous_execution]] — 결정 게이트 외 자율 진행
- [[feedback_elegant_no_code_dirtying]] — MEMBER 코드 무수정 invariant (회귀 회피 + 책임 분리)
