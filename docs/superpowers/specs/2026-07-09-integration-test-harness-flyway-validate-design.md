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

#### 3.2.1 실행 지점 — **반드시 test 트랜잭션 바깥(pre-transaction)** (C1)
> **치명 함정**: MySQL의 `TRUNCATE`는 DDL이라 **implicit COMMIT**을 유발한다. 스위트의 **~31개 IT가 class-level `@Transactional`(롤백형)** 인데(전체의 42%), Spring은 test-managed 트랜잭션으로 `@BeforeEach`까지 감싼다. cleaner를 `@BeforeEach`에서 같은 커넥션으로 돌리면 test 트랜잭션이 **강제 커밋**되어 롤백 격리가 붕괴 → 비결정 오염/flake. **디스커버리(§2, 41 failures)는 cleaner 없이 config만 뒤집은 측정이라 이 상호작용은 미검증이다.**

따라서:
- **기본(권장) — cleaner 전용 별도 `DataSource` 커넥션(autocommit=true)에서 TRUNCATE.** implicit commit이 **자기 커넥션에만** 적용되므로 TTEL 순서와 **무관하게** test tx를 절대 깨지 않는다(순서 실수 내성).
- 실행 훅은 `TestExecutionListener.beforeTestMethod()`(test body 전, MDL 회피 위해). test tx가 아직 안 열린 시점 + 별도 커넥션 → 이중 안전.
- 대안(비권장 — footgun): 같은 커넥션 + `@Order`로 `TransactionalTestExecutionListener`(order 4000)보다 앞(2000<order<4000)에 배치. 순서를 **한 번이라도 잘못 매기면(order>4000) C1이 조용히 재발**한다. 별도 커넥션 방식이 이 함정을 원천 제거하므로 기본값으로 확정.
- **`@BeforeEach`에 넣지 않는다.**

**설계 통찰**: `@Transactional` 롤백형 IT는 스스로 롤백하므로 사실 정리 대상이 아니다. cleaner가 실제로 지워야 할 것은 **직전 "커밋형" 테스트가 남긴 오염**뿐이고, 그 정리는 다음 test tx가 열리기 **전(pre-transaction)** 에 일어나야 한다 → 위 실행 지점과 일치.

#### 3.2.2 async quiescence — 커밋형 @Async 리스너 레이스 차단 (C2)
AFTER_COMMIT + `@Async` 리스너(`UserActivityLogListener` 등)는 커밋 후 별 스레드에서 `user_activity_log` 등에 INSERT한다. async 부작용을 **트리거하지만 await하지 않는** 테스트가 있으면, 그 late INSERT가 **다음 테스트 truncate 이후 착지**해 오염(예: `hasSize(1)`→2)하거나 MDL과 충돌한다.
- cleaner truncate **직전에 커밋행을 쓰는 async executor를 전부 idle 대기**(activeCount 0 + queue empty 폴링).
  - ⚠️ **executor는 단수가 아니라 최소 3개다**(반드시 전부 대기): `userActivityLogExecutor`·`webPushExecutor`(`AsyncConfig`), `vdjChatExecutor`(`VirtualDjChatAsyncConfig`). UAL 하나만 폴링하면 **webpush(`AnnouncementPushFlowIntegrationTest`)·vdj(`VirtualDjEventListenerIT`) 경로 레이스가 그대로 열린다** — 이 둘은 §2 실패목록에 실재. 구현은 `ThreadPoolTaskExecutor` 빈들을 컨텍스트에서 수집해 모두 quiesce.
  - **안정성 창(TOCTOU 방어)**: `ThreadPoolTaskExecutor`는 큐 dequeue↔activeCount 증가 사이 미세 창에서 (activeCount 0 ∧ queue empty)가 순간 참일 수 있다. 1회 스냅샷 대신 **연속 N회(예 3회, 짧은 간격) idle 관측**을 idle 조건으로 삼는다.
  - **타임아웃 만료 = fail-loud**(예외로 테스트 실패). 여전히 바쁜데 조용히 truncate 진행하면 C2 재발/침묵오염. 짧은 상한(예 수 초) 후 명시적 실패.
  - 이 quiescence를 pre-transaction 훅(§3.2.1)에 truncate **직전** 배치 → 직전 테스트 async tx의 MDL도 해소됨.
  - **스케줄러 blind-spot**: `@EnableScheduling` 크론(재생/vdj reconcile)은 `ThreadPoolTaskScheduler`(≠TPTE)에서 돌아 collect-all-TPTE 대상이 아니다(과다대기 회피 이점). 단 스케줄 잡이 스위트 중 커밋행을 쓰면 잔여 flake 벡터 — 발현 시 `test` 프로파일에서 스케줄링 비활성화로 대응.
- 병행: **커밋형 async 부작용을 내는 IT 목록화**(`UserActivityLog*Listener*IT` 8개 + 파티룸 생성/제재 + push + vdj 트리거) → 필요 시 Awaitility await 보강(`UserActivityLogListenerAdminPenaltyIT` 패턴을 표준으로).

#### 3.2.3 truncate 로직
1. `information_schema.tables`에서 현재 스키마(`pfplay_test`)의 **모든 base table** 동적 조회(하드코딩 목록 불요).
2. `PRESERVE_SET` + `flyway_schema_history` 제외 → **truncate 대상**.
3. `SET FOREIGN_KEY_CHECKS=0` → 각 대상 `TRUNCATE TABLE` → `SET FOREIGN_KEY_CHECKS=1` (cleaner 전용 커넥션 세션 스코프).

- **`PRESERVE_SET`(레퍼런스 시드, 보존)**: `avatar_body_resource`, `avatar_face_resource`, `avatar_icon_resource`, `system_config`, 가상DJ 설정 시드 테이블(V27/V28/V29). 제품 코드가 읽는 참조 데이터(예: 기본 아바타). 최종 목록은 §8 오픈결정.
- **truncate 대상(비보존)**: 위 제외 전부. 특히 이중목적 시드 테이블 `user_account`·`member`·`administrator`(V5)도 truncate. 근거: 최빈 트랜잭션 테이블이라 보존 시 누적/충돌; 기존 IT는 create-drop(무시드) 전제라 V5 시드 비의존; 어드민 필요 테스트는 자체 생성. 부수효과로 `administrator`/`member` 관련 AssertionError가 빈-테이블 전제 복원으로 해소.

> **왜 "전부 truncate + test-seed.sql"이 아니라 "보존"인가**: 사용자 결정(유지보수 시드 SQL 회피). 동적 조회 + 소수 명시 `PRESERVE_SET`으로 대부분 자기유지. 참조 시드는 Flyway가 제공.

### 3.3 레퍼런스-변경 테스트의 잔여 처리 (S3/S4)
`PRESERVE_SET` 테이블은 truncate하지 않으므로, 여기에 **커밋으로 쓰는** 테스트만 교차오염원이 된다. **핵심 좁히기**: class-level `@Transactional`(롤백형) 테스트는 보존 테이블에 써도 **스스로 롤백**되어 오염되지 않는다(확인: `SystemConfigRepositoryIntegrationTest`, `SystemAnnouncementRepository*Test`, `AvatarAdminActionListenerIT` 전부 `@Transactional`). 따라서 대상은 **"보존 테이블에 커밋하는 비-`@Transactional` IT"** 로 한정된다.
- 구현 1단계: 이 부분집합을 **실제로 열거**(grep: PRESERVE_SET 테이블 repo save/insert를 쓰면서 class-level `@Transactional`이 없는 IT). 예상 소수.
- 처리: (a) 해당 테스트 `@AfterEach` 자체 정리, 또는 (b) 그 클래스에 한해 참조 테이블을 클래스-스코프 truncate+재시드. 실패별 택1.
- **비대칭 주의(S4)**: 보존 테이블 자식행은 FK-off truncate로 지워지지만 보존 테이블 자신의 커밋 행은 안 지워짐 — 위 열거 대상이 이 유일 경로. count-단언 비전제(§3.2.3)가 깨지는 지점이므로 명시적으로 다룬다.

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
- 수정: `app/src/test/resources/application-test.yml`(설정 전환: validate + flyway)
- 생성: `.../common/DatabaseCleaner.java`(동적 truncate + PRESERVE_SET, 전용 커넥션/JdbcTemplate)
- 생성: `.../common/DatabaseCleanupTestExecutionListener.java`(`beforeTestMethod`에서 async quiescence + cleaner 호출; **Transactional TEL보다 앞 순서**)
- 수정: `AbstractIntegrationTest`(`@TestExecutionListeners`로 위 리스너 등록 — `@BeforeEach` 아님)
- 수정: 잔여 실패 IT들(§5.0 재측정으로 확정 — 개별수정/await 보강)
- (검토) 기존 IT들의 자체 `@AfterEach deleteAll` 중복 정리(N1, §8-5 방침 따라)
- 수정: `.github/workflows/ci-test.yml`(integrationTest 스텝; 74 초록 뒤 별 커밋/후속)

## 5. 검증 (DoD 게이트)
0a. **cleaner 결정론 마이크로 검증(C1, 재측정 전 선행)**: 전량 실행 전에, cleaner가 롤백 격리를 **깨지 않음**을 결정론적으로 단언하는 전용 테스트를 먼저 통과시킨다 — **핵심 단언**은 "인접 두 (커밋형) 메서드가 서로의 잔재를 관측하지 않음(clean 시작)" — cleaner가 실제로 하는 일. 부수로 "롤백형 메서드 write가 같은 메서드 롤백으로 소멸"(cleaner가 tx를 안 깬다는 방증)도 단언. **1회 초록은 flake 부재의 증명이 아니므로**(C1 발현형=비결정), 이 마이크로 검증 + 31개 반복/스트레스 실행으로 "격리가 살아있음"을 단언한다.
0b. **cleaner 적용 후 재측정(S2)**: config 전환 + `DatabaseCleaner`(§3.2 pre-transaction, 별도 커넥션)까지 넣은 상태로 `:app:integrationTest --continue` 재실행 → 실패 **재분류**(자동해소 vs 개별수정). §2의 41/14는 cleaner **이전** 수치라 신뢰 불가 — 이 재측정이 개별수정 범위의 진실원천.
1. `:app:integrationTest` **전량 초록**(0 fail/0 error) — 로컬 uncontended, 안정성 위해 **연속 2회 이상** 초록.
2. `:app:test` 회귀 무변(유닛/웹 여전히 초록).
3. CI 배선 후 실제 워크플로에서 integrationTest 초록(또는 로컬로 CI 명령 재현).
4. validate 상시 활성 → 이후 마이그레이션 drift는 IT 부팅에서 즉시 검출.

## 6. Non-goals
- 제품 코드 로직 변경(순수 테스트/설정/CI).
- 개별 IT의 테스트 설계 리팩토링(격리·시드 대응에 필요한 최소 수정만).
- 어드민 파티룸 행동분석 기능(별 브랜치 `feat/admin-partyroom-behavior-analytics`, 무관).
- Testcontainers 컨테이너 재사용 전략 대개편(현 싱글턴 유지, 데이터 격리만 추가).

## 7. 리스크 & 완화
- **⚠️직렬 실행이 불변식(S1)**: 공유 Testcontainers 스키마 + 전역 truncate는 **직렬 실행에서만 안전**하다. 현재 `build.gradle`이 `maxParallelForks=1` + `forkEvery=0`(확인)이라 안전. **누군가 속도 위해 병렬화하면 즉시 치명적**(fork 간 스키마 truncate 상호파괴). → `integrationTest` 태스크에 "병렬화 금지(스키마-per-fork 없인 불가)" 주석/가드 고정, Non-goals에도 명시.
- **성능/CI 시간(S5)**: 매 테스트 40+ 테이블 truncate(FK off) — TRUNCATE 빠름, 테스트당 수십 ms 허용. 별개로 **`@MockBean` 사용 IT는 별도 Spring 컨텍스트 캐시 엔트리**를 만들어 캐시 스래싱→다수 풀부팅 유발(74 IT). CI 소요는 이 컨텍스트 파편화가 지배 → **DoD 재측정(§5.0) 때 실제 벽시계 측정**해 CI 예산 확정(Open Decision #3와 연동). 최적화("변경 테이블만 truncate", 컨텍스트 통합)는 후속.
- **PRESERVE_SET 누락/과다**: 적게 보존하면 참조 데이터 소실(부팅 실패), 많이 보존하면 오염. §5.0 재측정으로 조정.
- **CI 러너 Docker**: GitHub-hosted `ubuntu-latest`는 Docker 지원(확인) → Testcontainers 동작. self-hosted면 데몬 확인 필요.
- **`@BeforeAll` 커밋 픽스처 소실**: cleaner는 **메서드마다** pre-transaction으로 truncate하므로, 클래스 1회 `@BeforeAll`에서 **커밋**으로 시드한 공유 픽스처는 2번째 메서드부터 소실된다. create-drop 전제 IT엔 드물지만 전량 전환 시 잠재 — 커밋형 `@BeforeAll` 시드는 **메서드 스코프로 이전**하거나 그 대상을 보존대상화(이때 §8-1 FK-closure 준수)해야 한다(§5.0b 재측정에서 발현 시 처리).
- **Flyway 슬롯**: 본 브랜치는 마이그레이션 무추가라 무관. 오히려 IT가 Flyway를 타므로 향후 슬롯 충돌 PR은 IT 부팅에서 즉시 red(이점).

## 8. 오픈 결정 (스펙 리뷰에서 확정)
1. `PRESERVE_SET` 최종 목록(가상DJ 설정 테이블 포함 여부, `system_config` 보존/재시드). **FK-closure 기준(필수)**: 보존 테이블은 **truncate 대상으로 나가는 outgoing FK가 없어야** 한다. 있으면 FK_CHECKS=0로 대상을 비우는 순간 보존 테이블 행에 **dangling 참조**가 남고 MySQL은 FK_CHECKS=1 복원 시 **재검증하지 않아 조용히 잔존**한다. V27/V28/V29 가상DJ 설정 시드의 outgoing FK를 확인해, 참조 대상이 truncate 대상이면 그 대상도 보존(또는 보존 테이블을 truncate 대상으로 강등)해 FK-closure를 만족시킨다.
2. 레퍼런스-변경 테스트(§3.3): "보존 테이블에 커밋하는 비-`@Transactional` IT" 열거 후 자체정리 vs 클래스스코프 재시드 택1.
3. CI: `test`와 `integrationTest`를 한 스텝으로 묶을지 분리할지 — §5.0 벽시계 측정 후 결정. 배선은 74 초록 확인 뒤 **별 커밋/후속 PR** 권장(first-run flake가 dev 자동배포 게이트 막지 않게, N2).
4. **cleaner 실행 지점(C1 — 확정)**: **별도 autocommit 커넥션 + `beforeTestMethod` 훅**을 기본으로 확정(§3.2.1). TEL-ordering 단독은 footgun이라 채택 안 함. async quiescence(§3.2.2, executor 전부)를 이 훅에 truncate 직전 포함. **`@BeforeEach` 불가.** (남은 결정: quiescence 타임아웃 상한값·fail-loud 메시지 등 세부.)
5. 기존 테스트들의 자체 `@AfterEach deleteAll`(N1): cleaner 도입 후 중복이 되면 제거할지 존치할지 정리 방침.
