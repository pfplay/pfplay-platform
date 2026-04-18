# Flyway Migration Design

## Context

PFPlay backend (Spring Boot 3.2, MySQL, Java 21, Gradle multi-module)은 현재 `ddl-auto: create`로 스키마를 관리하고 있다. 운영 시작 전이며, 안전한 스키마 관리와 향후 마이그레이션을 위해 Flyway를 도입한다.

- 엔티티: 21개 (user 8, playlist 2, app/party 11)
- 모듈: common, user, playlist, app, realtime
- 배포: 단일 app 모듈 (Docker + GCP VM)

## Approach

Hibernate DDL 캡처 기반 V1 baseline + `ddl-auto: validate` + Flyway 자동 마이그레이션.

운영 전이므로 `baseline-on-migrate`는 사용하지 않고, `V1__init_schema.sql`로 깨끗하게 시작한다.

## 1. Dependencies

`app/build.gradle`에만 추가. 버전은 Spring Boot BOM이 관리하므로 명시하지 않는다.

```gradle
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-mysql'
```

다른 모듈(common, user, playlist, realtime)에는 추가하지 않는다.

## 2. Profile Configuration

모든 프로필은 `application.yml` 단일 파일 내 `---` 구분자로 나뉜 multi-document YAML이다.
현재 프로파일 구성: **local / dev / staging / prod** 4개 + `common` 공통 베이스 + 별도 `test`(파일).

- `common`: local/dev/staging/prod 모두의 공통 기반
- `local`: default 프로파일. `common` 상속만 하며 별도 오버라이드 없음 → common 변경이 그대로 적용됨. 별도 작업 불필요.
- `dev`, `staging`, `prod`: 각자 오버라이드 섹션 보유
- staging/prod는 이미 `ddl-auto: validate`, `sql.init.mode: never`. dev는 현재 `ddl-auto: create` + `sql.init.mode: never`.

### common (모든 환경의 공통 기반, local 포함)

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate        # create → validate
  sql:
    init:
      mode: never               # always → never (Flyway가 대체)
  flyway:
    enabled: true
    encoding: UTF-8             # Windows + 한국어 체크섬 불일치 방지
    clean-disabled: true        # Flyway 9.x 기본값이지만 명시적 안전장치
```

### dev

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate        # create → validate
  flyway:
    enabled: true
    encoding: UTF-8
    clean-disabled: true
```

### staging

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate        # 기존 유지
  flyway:
    enabled: true
    encoding: UTF-8
    clean-disabled: true
```

### prod

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate        # 기존 유지
  flyway:
    enabled: true
    encoding: UTF-8
    clean-disabled: true
```

### test (application-test.yml)

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop     # 기존 유지
  flyway:
    enabled: false              # 테스트는 Hibernate가 스키마 관리
```

## 3. Migration Files

### Location

`app/src/main/resources/db/migration/` (Flyway 기본 `classpath:db/migration` 경로)

멀티모듈이지만 단일 app 배포이므로 `spring.flyway.locations` 별도 설정 불필요. 모든 마이그레이션 파일은 app 모듈에 위치한다.

### Naming Convention

```
V1__init_schema.sql
V2__add_xxx_column.sql
V3__create_xxx_table.sql
```

- `V` + 버전 + `__` (double underscore) + 설명 + `.sql`
- 한번 적용된 파일은 절대 수정하지 않는다 (체크섬 불일치 에러 발생)

### V1 Baseline 생성 방식

1. 로컬에서 `ddl-auto: create`로 앱 기동하여 MySQL에 스키마 생성
2. `mysqldump --no-data pfplay > create.sql`로 DDL 추출 (로그 파싱보다 신뢰성 높음)
3. 수동으로 다음을 보강:
   - 모든 테이블에 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` 확인
   - 인덱스, 유니크 제약조건 확인
   - mysqldump 메타 주석 제거
4. `V1__init_schema.sql`로 저장

### V1 검증 (첫 실행 확인)

1. 빈 DB에 `V1__init_schema.sql`을 Flyway로 적용
2. `ddl-auto: validate`로 앱 기동
3. Hibernate validate 통과 확인 → 실패 시 V1 SQL 수정 후 DB 초기화하여 재시도
4. 통과하면 V1 확정

Hibernate DDL이 생략하는 charset/engine 선언을 반드시 추가해야 한다.

### 이후 워크플로우

1. 엔티티 변경
2. 대응하는 `V{n}__description.sql` 작성
3. 로컬에서 기동 → Flyway 실행 + Hibernate validate 검증
4. 불일치 시 기동 실패 → 마이그레이션 수정 후 재시도

## 4. flyway_schema_history

Flyway는 `flyway_schema_history` 메타 테이블을 자동 생성하여 마이그레이션 이력을 추적한다. 이 테이블은 JPA 엔티티로 관리하지 않으며, Flyway가 단독 관리한다. 기존 21개 엔티티 테이블 + 1개 메타 테이블 = 총 22개 테이블.

## 5. Startup Order

```
App Start → Flyway Migration → Hibernate Validate → Application Ready
```

- Flyway가 먼저 스키마를 생성/변경
- Hibernate가 엔티티와 스키마 일치 여부 검증
- 어느 단계든 실패하면 앱 기동 중단 (fail-fast)

## 6. Rollback Strategy

Flyway Community는 자동 롤백 미지원. MySQL은 트랜잭셔널 DDL을 지원하지 않으므로 부분 실패 가능.

- 롤백 필요 시 `V{n+1}__rollback_xxx.sql`로 역방향 DDL 작성
- 로컬에서 반드시 마이그레이션 테스트 후 배포

## 7. CI/CD

- `ci-test.yml`: 테스트만 실행 (flyway.enabled=false) → 변경 없음
- `build-and-deploy.yml`: 앱 기동 시 Flyway 자동 실행 → 변경 없음
- 별도 마이그레이션 스크립트나 배포 단계 추가 불필요

## 8. Future Considerations

- 인테그레이션 테스트에서 Flyway 활성화 (Testcontainers + flyway.enabled=true)
- 마이그레이션 파일이 많아지면 repeatable migration (R__xxx.sql) 활용 검토
- Flyway Teams/Enterprise 도입 시 undo migration 활용 가능

## Summary of Changes

| 파일 | 변경 내용 |
|------|-----------|
| `app/build.gradle` | flyway-core, flyway-mysql 의존성 추가 |
| `application.yml` common 프로필 섹션 | ddl-auto: validate, sql.init.mode: never, flyway 설정 추가 |
| `application.yml` dev 프로필 섹션 | ddl-auto: validate, flyway 설정 추가 |
| `application.yml` staging 프로필 섹션 | flyway 설정 추가 |
| `application.yml` prod 프로필 섹션 | flyway 설정 추가 |
| `app/src/test/resources/application-test.yml` | flyway.enabled: false 추가 |
| `app/src/main/resources/db/migration/V1__init_schema.sql` | Hibernate DDL 캡처 + charset 보강 |
| `docs/migration/V20260215__update_avatar_body_positions.sql` | deprecated — 삭제 (V1 baseline 캡처 시점엔 이미 스키마에 반영되어 있음) |

> `local` 프로파일은 `common`을 상속만 하므로 common 변경으로 자동 커버됨. 별도 파일/섹션 변경 없음.
