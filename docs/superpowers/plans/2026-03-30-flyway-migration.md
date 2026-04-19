# Flyway Migration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ddl-auto: create`를 제거하고 Flyway 기반 스키마 마이그레이션으로 전환한다.

**Architecture:** Flyway가 앱 기동 시 `db/migration/` 내 SQL 스크립트를 순서대로 실행하고, Hibernate `validate`가 엔티티-스키마 일치를 검증한다.

**Tech Stack:** Spring Boot 3.2, Flyway 9.x (BOM 관리), MySQL, Gradle

**Spec:** `docs/superpowers/specs/2026-03-30-flyway-migration-design.md`

---

## Chunk 1: Dependencies & Configuration

### Task 1: Flyway 의존성 및 모든 설정 변경

**Files:**
- Modify: `app/build.gradle` (dependencies 블록, line 11 부근)
- Modify: `app/src/main/resources/application.yml` (common/dev/staging/prod 프로필 섹션)
- Modify: `app/src/test/resources/application-test.yml`

> **프로파일 구성 확인 (2026-04-19 기준):**
> 현재 4개 환경 프로파일이 있다: `local`(default) / `dev` / `staging` / `prod`.
> 모두 `common` 공통 베이스를 상속. `local`은 common 외 별도 오버라이드가 없으므로 common 변경으로 자동 커버되며 **별도 Step 불필요**.

- [ ] **Step 1: app/build.gradle에 Flyway 의존성 추가**

`dependencies` 블록의 `implementation project(':realtime')` 아래에 추가:

```gradle
// Flyway
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-mysql'
```

버전은 명시하지 않는다. Spring Boot BOM이 호환 버전을 관리한다.

- [ ] **Step 2: 빌드 확인**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: common 프로필에서 ddl-auto, sql.init.mode 변경 + flyway 설정 추가**

`app/src/main/resources/application.yml` 파일의 common 프로필 섹션에서:

변경 전:
```yaml
  jpa:
    show-sql: true
    hibernate:
      ddl-auto: create
    properties:
      hibernate:
        format_sql: true

  sql:
    init:
      mode: always
```

변경 후:
```yaml
  jpa:
    show-sql: true
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        format_sql: true

  sql:
    init:
      mode: never

  flyway:
    enabled: true
    encoding: UTF-8
    clean-disabled: true
```

- [ ] **Step 4: dev 프로필에서 ddl-auto 변경 + flyway 설정 추가**

변경 전:
```yaml
  jpa:
    show-sql: false
    hibernate:
      ddl-auto: create
    properties:
      hibernate:
        format_sql: false

  sql:
    init:
      mode: never
```

변경 후:
```yaml
  jpa:
    show-sql: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        format_sql: false

  sql:
    init:
      mode: never

  flyway:
    enabled: true
    encoding: UTF-8
    clean-disabled: true
```

- [ ] **Step 5: staging 프로필에 flyway 설정 추가**

staging 프로필의 `sql:` 블록 아래에 추가:

```yaml
  flyway:
    enabled: true
    encoding: UTF-8
    clean-disabled: true
```

staging은 이미 `ddl-auto: validate`, `sql.init.mode: never`이므로 jpa/sql 설정 변경 불필요.

- [ ] **Step 6: prod 프로필에 flyway 설정 추가**

prod 프로필의 `sql:` 블록 아래에 추가:

```yaml
  flyway:
    enabled: true
    encoding: UTF-8
    clean-disabled: true
```

prod는 이미 `ddl-auto: validate`이므로 jpa 설정 변경 불필요.

- [ ] **Step 7: application-test.yml에 flyway 비활성화**

`app/src/test/resources/application-test.yml`의 `spring:` 블록 내, `sql:` 섹션 뒤에 추가:

```yaml
  flyway:
    enabled: false
```

기존 `ddl-auto: create-drop`은 유지한다.

- [ ] **Step 8: deprecated 마이그레이션 파일 제거**

`docs/migration/V20260215__update_avatar_body_positions.sql`은 Flyway 도입 이전에 수동 적용된 ad-hoc SQL이며, `docs/` 아래에 있어 Flyway 대상도 아니다. V1 baseline을 캡처할 시점엔 이 SQL의 결과가 이미 스키마에 반영되어 있으므로 V1에 자연 포함된다. 파일을 삭제한다:

```bash
git rm docs/migration/V20260215__update_avatar_body_positions.sql
```

이후 `docs/migration/` 디렉토리가 빈 경우 함께 제거 (git은 빈 디렉토리 추적 안 하므로 파일 삭제만으로 정리됨).

- [ ] **Step 9: Commit**

```bash
git add app/build.gradle app/src/main/resources/application.yml app/src/test/resources/application-test.yml
# Step 8의 git rm은 이미 스테이징되어 있으므로 별도 add 불필요
git commit -m "chore: add Flyway dependencies and configure all profiles

- Add flyway-core, flyway-mysql to app/build.gradle
- Configure common/dev/staging/prod profiles + test disable
- Remove deprecated docs/migration/V20260215 (ad-hoc SQL pre-Flyway)"
```

---

## Chunk 2: V1 Baseline Migration

### Task 2: Hibernate DDL 캡처 및 V1 마이그레이션 생성

**Files:**
- Create: `app/src/main/resources/db/migration/V1__init_schema.sql`

이 태스크는 Hibernate가 생성하는 DDL을 기반으로 V1 SQL을 작성한다.
DDL 캡처를 위해 임시로 `ddl-auto: create`를 사용하되, 태스크 완료 후 원복한다.

- [ ] **Step 1: DDL 캡처를 위한 임시 설정**

`app/src/main/resources/application.yml` common 프로필에 스크립트 생성 속성을 임시 추가:

```yaml
  jpa:
    show-sql: true
    hibernate:
      ddl-auto: create
    properties:
      hibernate:
        format_sql: true
        jakarta.persistence.schema-generation.scripts.action: create
        jakarta.persistence.schema-generation.scripts.create-target: create.sql
```

- [ ] **Step 2: 앱 기동하여 DDL 파일 생성**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:bootRun`

앱이 기동되면 루트 디렉토리에 `create.sql` 파일이 생성된다. 기동 확인 후 Ctrl+C로 종료.
(DB 연결 실패로 기동이 안 되더라도 create.sql은 생성될 수 있다.)

- [ ] **Step 3: create.sql을 V1 마이그레이션으로 변환**

`create.sql` 내용을 `app/src/main/resources/db/migration/V1__init_schema.sql`로 복사하고 다음을 수정:

1. 모든 `CREATE TABLE` 문 끝에 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` 추가
2. Hibernate가 생성한 `create sequence` 문이 있으면 제거 (MySQL은 sequence 미사용)
3. 테이블 생성 순서가 FK 의존성을 충족하는지 확인 (참조 대상 테이블이 먼저)
4. 파일 인코딩이 UTF-8인지 확인

- [ ] **Step 4: 임시 설정 원복**

Step 1에서 추가한 `jakarta.persistence.schema-generation.scripts.*` 속성을 제거하고, `ddl-auto`를 `validate`로 되돌린다:

```yaml
  jpa:
    show-sql: true
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        format_sql: true
```

루트 디렉토리의 `create.sql` 파일을 삭제한다:

```bash
rm create.sql
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/resources/db/migration/V1__init_schema.sql
git add app/src/main/resources/application.yml
git commit -m "feat: add V1 baseline schema migration"
```

---

## Chunk 3: Verification

### Task 3: V1 마이그레이션 검증

이 태스크는 로컬 MySQL에서 V1 마이그레이션이 정상 동작하는지 검증한다.

- [ ] **Step 1: 로컬 DB 초기화**

로컬 MySQL에서 pfplay 데이터베이스를 재생성:

```bash
mysql -u pfplay -p -e "DROP DATABASE IF EXISTS pfplay; CREATE DATABASE pfplay CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

또는 Docker 환경이라면 컨테이너를 재시작한다.

- [ ] **Step 2: 앱 기동하여 Flyway + validate 검증**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :app:bootRun`

Expected:
- 로그에 `Successfully applied 1 migration to schema "pfplay"` 출력
- Hibernate `validate` 통과
- 앱 정상 기동

실패 시: V1 SQL의 컬럼 타입/이름을 Hibernate 엔티티와 비교하여 수정 후 재시도.
(DB를 DROP 후 재생성해야 Flyway가 처음부터 다시 실행됨)

- [ ] **Step 3: 테스트 실행**

Run: `JAVA_HOME="C:/Users/Eisen/.jdks/ms-21.0.7" ./gradlew :common:test :playlist:test :user:test :app:test`

Expected: 모든 테스트 통과 (테스트는 flyway.enabled=false이므로 Flyway 무관하게 동작)

검증 태스크이므로 별도 커밋 불필요. V1 SQL 수정이 필요했다면 Task 2의 커밋을 amend하거나 새 커밋을 생성한다.
