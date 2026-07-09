# 통합 테스트 하네스 현대화 — Flyway+validate 전환 · 격리 · CI 배선 설계

- 작성일: 2026-07-09
- 범위: pfplay-platform 통합 테스트(IT) 인프라 (제품 코드 변경 없음, 테스트/설정/CI만)
- 상태: Draft → 스펙 리뷰 대기

## 1. 배경 & 문제

pfplay-platform의 통합 테스트(`@Tag("integration")`, `AbstractIntegrationTest` 상속, **74개 클래스**)는 현재 다음 하네스로 돈다:

- `application-test.yml`: `spring.jpa.hibernate.ddl-auto: create-drop`, `spring.flyway.enabled: false`, `spring.sql.init.mode: never`
- Testcontainers MySQL/Redis는 **정적 싱글턴**(`TestContainerConfig`, 프로세스당 1회 start, 전 IT 공유)

이 구성의 근본 결함 두 가지:

1. **하네스가 프로덕션과 불일치 → 마이그레이션 drift를 가림.** 스키마를 Flyway가 아니라 **엔티티에서 파생**(create-drop)하므로, 실제 마이그레이션(V1~)·시드가 테스트에서 검증되지 않는다. 프로덕션/로컬/스테이징/프로드 프로파일은 전부 `ddl-auto: validate` + `flyway.enabled: true`인데, IT만 다르다. (참고: `reference_ddl_auto_create_drop_hides_migration_drift`)
   - 부작용: 시드 데이터(기본 아바타 등)가 테스트 DB에 없어, 멤버 프로비저닝 경로(사인업·어드민 로그인)가 `IllegalStateException: 기본 아바타 바디 리소스가 존재하지 않습니다` / 500으로 **현재 4개 IT가 red**. 제품은 정상(실 환경은 Flyway 시드 적용). 즉 **테스트 전용 위양성**.

2. **CI가 통합 테스트를 아예 실행하지 않는다.** `ci-test.yml`은 `./gradlew test`만 실행하고, 루트 `build.gradle`의 `test` 태스크는 `excludeTags 'integration'`. `integrationTest`(`includeTags 'integration'`)는 **어느 워크플로도 호출하지 않음**(deploy는 `-x test`). → **통합 게이트가 무방비**, 위 red가 아무도 모르게 방치됨.

**목표(DoD)**: IT를 Flyway+validate로 전환 → 74개 클래스 전부 초록 → `integrationTest`를 CI에 배선.

## 2. 디스커버리 (실측)

origin/develop 워크트리에서 `application-test.yml`을 `ddl-auto: validate` + `flyway.enabled: true`로 뒤집고 `:app:integrationTest --continue` 전량 실행한 결과:

- **279 tests · 41 failures · 0 errors** (0 errors = **컨텍스트 부팅 정상 = drift 없음**. validate가 V1~V34 전체에 통과. 실패는 전부 데이터/상태 계열)
- 기존 red 4건(아바타 시드)은 **사라짐**(시드가 들어와 통과) — 전환이 오히려 이들을 **고침**.
- 대신 이전엔 숨어있던 **41 failures / 14 클래스**가 드러남:

| 분류 | 원인 | 클래스(대표) |
|---|---|---|
| DataIntegrity/Constraint (~11클래스) | 공유·영속 스키마의 **교차오염 + 시드 유니크 충돌 + FK 강제** | AvatarAdminActionListenerIT, PartyroomAdminActionListenerIT, UserActivityLogListenerAdminPenaltyIT, SystemAnnouncementRepository(Impl/Test)IT, AdminReportCommandServiceIT, AnnouncementPushFlowIntegrationTest, PartyroomCounterListenerIT, BotAvatarAdminServiceIT, VirtualDjEventListenerIT, VirtualDjOrchestratorIT |
| AssertionError (~3클래스) | **빈 테이블 전제**가 시드 존재로 깨짐 | AdministratorRepositoryIntegrationTest, AdminMemberWithdrawCommandServiceIT, PartyroomRepositoryAtomicUpdateIT |

**루트 원인(단일)**: create-drop에선 새 Spring 컨텍스트마다 스키마가 리셋됐으나, validate+Flyway에선 **공유 Testcontainers 스키마가 74개 클래스 전반에 영속**하고 **시드 데이터가 존재**한다. 그래서 자기격리가 불완전한 테스트가 서로 충돌한다. 기존 IT들은 전부 **빈 스키마(시드 없음)** 전제로 작성됐다.

## 3. 설계

### 3.1 설정 전환
`app/src/test/resources/application-test.yml`:
- `ddl-auto: create-drop` → **`validate`**
- `flyway.enabled: false` → **`true`**
- `sql.init.mode: never`는 유지(Flyway가 시드 담당).

이로써 IT가 프로덕션과 동일한 스키마+시드 경로를 타고, validate가 drift를 상시 검증한다.

### 3.2 테스트 격리 — `DatabaseCleaner` (동적 truncate + 레퍼런스 보존)

공유·영속 스키마에서 각 테스트에 "깨끗한 슬레이트"를 복원한다.

- **위치**: `AbstractIntegrationTest`의 `@BeforeEach`에서 호출(모든 IT 상속). 컴포넌트는 `common` 테스트 소스의 `DatabaseCleaner`(주입: `JdbcTemplate`/`EntityManager`).
- **동작**:
  1. `information_schema.tables`에서 현재 스키마(`pfplay_test`)의 **모든 base table**을 동적 조회 → 하드코딩 테이블 목록 불요(새 테이블 자동 포함).
  2. `PRESERVE_SET` + `flyway_schema_history`를 제외한 나머지를 **truncate 대상**으로.
  3. `SET FOREIGN_KEY_CHECKS=0` → 각 대상 `TRUNCATE TABLE` → `SET FOREIGN_KEY_CHECKS=1`.
- **`PRESERVE_SET`(레퍼런스 시드, 보존)**: `avatar_body_resource`, `avatar_face_resource`, `avatar_icon_resource`, `system_config`, 그리고 가상DJ 설정 시드 테이블(V27/V28/V29 대상). 이들은 **제품 코드가 읽는 참조 데이터**(예: 기본 아바타)이고 대부분의 테스트가 count-단언하지 않는다.
- **truncate 대상(비보존)**: 위를 제외한 전부. 특히 **이중목적 시드 테이블 `user_account`·`member`·`administrator`(V5 슈퍼어드민 시드)도 truncate**한다. 근거:
  - 이들은 가장 빈번한 트랜잭션 테이블 → 보존하면 누적/충돌("Duplicate entry").
  - 기존 IT는 create-drop(시드 없음) 전제라 V5 시드에 의존하지 않는다. 어드민이 필요한 테스트(예: `AdminPasswordChangeIntegrationTest`)는 이미 자체 생성한다.
  - 부수효과: `administrator`/`member` truncate로 **AssertionError 3건 중 시드-존재 계열이 자동 해소**(빈 테이블 전제 복원).

> **왜 "전부 truncate + test-seed.sql"이 아니라 "보존"인가**: 사용자 결정(유지보수할 시드 SQL 회피). 동적 조회 + 소수 명시 `PRESERVE_SET`으로 대부분 자기유지된다. 참조 시드는 Flyway가 제공(테스트가 재시드하지 않음).

### 3.3 레퍼런스-변경 테스트의 잔여 처리
`PRESERVE_SET` 테이블을 **직접 변경**하는 소수 테스트(아바타 어드민·설정 계열: `AvatarAdminActionListenerIT`, `BotAvatarAdminServiceIT` 등)는 보존 정책상 교차오염 여지가 있다. 처리:
- 우선순위 1: 해당 테스트가 변경분을 **자체 정리**(`@AfterEach`)하도록 보강.
- 대안: 그 테스트 클래스에 한해 해당 참조 테이블을 **truncate+최소 재시드**(클래스 스코프 훅). 스펙 구현 시 실패 원인별로 택1.

### 3.4 잔여 실패 개별 수정
truncate 도입 후에도 남는 실패(시드 유니크 충돌·FK·단언)는 클래스별로 원인 규명해 수정:
- FK "Cannot add child row": 부모 행 시드 누락 → 테스트가 부모를 먼저 시드하도록(또는 참조 무결성에 맞게 데이터 구성).
- "Duplicate entry": 참조 시드와 충돌하는 고정 키 → 테스트 전용 키 범위(예: 990000+)로 회피.
- AssertionError 잔여: 시드-인지로 기대값 조정.

### 3.5 CI 배선
`.github/workflows/ci-test.yml`에 `integrationTest` 스텝 추가:
- 러너에 Docker 가용(Testcontainers) 확인 — GitHub-hosted `ubuntu-latest`는 Docker 지원. `./gradlew :app:integrationTest` 실행.
- `test`와 별개 스텝(또는 `./gradlew test integrationTest`). 실패 시 빨간불.
- **선행조건**: §3.1~3.4로 74개가 초록이 된 뒤 배선(아니면 CI가 즉시 red).

## 4. 파일 맵 (예상)
- 수정: `app/src/test/resources/application-test.yml`(설정 전환)
- 생성: `.../common/DatabaseCleaner.java`(또는 `common` 테스트 유틸 패키지)
- 수정: `AbstractIntegrationTest`(`@BeforeEach`에서 cleaner 호출)
- 수정: 잔여 실패 IT들(§3.3~3.4, 최대 14클래스 — 다수는 §3.2로 자동 해소 예상)
- 수정: `.github/workflows/ci-test.yml`(integrationTest 스텝)

## 5. 검증 (DoD 게이트)
1. `:app:integrationTest` **전량 초록**(279+ tests, 0 fail/0 error) — 로컬 uncontended 1회.
2. `:app:test` 회귀 무변(유닛/웹 여전히 초록).
3. CI 배선 후 실제 워크플로에서 integrationTest 초록(또는 로컬로 CI 명령 재현).
4. validate 상시 활성 → 이후 마이그레이션 drift는 IT 부팅에서 즉시 검출.

## 6. Non-goals
- 제품 코드 로직 변경(순수 테스트/설정/CI).
- 개별 IT의 테스트 설계 리팩토링(격리·시드 대응에 필요한 최소 수정만).
- 어드민 파티룸 행동분석 기능(별 브랜치 `feat/admin-partyroom-behavior-analytics`, 무관).
- Testcontainers 컨테이너 재사용 전략 대개편(현 싱글턴 유지, 데이터 격리만 추가).

## 7. 리스크 & 완화
- **성능**: 매 테스트 40+ 테이블 truncate(FK off) — TRUNCATE는 빠르므로 테스트당 수십 ms 예상, 허용. 관측 후 필요 시 "변경된 테이블만 truncate"로 최적화(후속).
- **PRESERVE_SET 누락/과다**: 너무 적게 보존하면 제품-코드 참조 데이터 소실(부팅 실패), 너무 많이 보존하면 오염. §2 실측 실패로 검증하며 조정.
- **CI 러너 Docker**: self-hosted 러너면 Docker 데몬 확인 필요. GitHub-hosted면 무관.
- **Flyway 슬롯(브랜치 교차)**: 본 브랜치는 마이그레이션을 추가하지 않으므로 슬롯 충돌 무관(단, IT가 Flyway를 타므로, 향후 슬롯 충돌 PR은 IT 부팅에서 즉시 red — 오히려 이점).

## 8. 오픈 결정 (스펙 리뷰에서 확정)
1. `PRESERVE_SET` 최종 목록(가상DJ 설정 테이블 포함 여부, `system_config` 보존/재시드).
2. 레퍼런스-변경 테스트(§3.3): 자체정리 vs 클래스스코프 재시드 — 실패별 택1 기준.
3. CI: `test`와 `integrationTest`를 한 스텝(`gradlew test integrationTest`)으로 묶을지 분리할지.
4. `DatabaseCleaner` 구현 레벨: JDBC 직접 vs Hibernate 세션 — 트랜잭션 경계/AFTER_COMMIT 리스너 IT와의 상호작용 고려.
