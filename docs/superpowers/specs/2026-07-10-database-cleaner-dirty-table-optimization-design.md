# DatabaseCleaner 더티-테이블 최적화 설계

- 작성일: 2026-07-10
- 브랜치: `test/it-harness-flyway-validate` (IT 하네스 현대화 후속)
- 상태: 설계 승인 → 스펙 리뷰 대기

## 1. 배경 / 문제

IT 하네스 현대화(create-drop → Flyway+validate)에서 도입한 `DatabaseCleaner`는
**각 테스트 메서드 전(`beforeTestMethod`)에 PRESERVE_SET을 제외한 모든 base table(~70개)을
무조건 `TRUNCATE`** 한다. InnoDB의 `TRUNCATE`는 DDL(암시적 커밋)이라 테이블마다 스토리지
커밋(fsync)을 유발한다.

- **리눅스 CI**: fsync가 밀리초 → 전량 IT 4분39초. 문제 없음.
- **Docker Desktop for Windows(virtiofs/overlay)**: fsync가 **테이블당 1~2초** → 매 메서드
  ~70 TRUNCATE × 282 메서드 = 수십 분. 로컬 개발자가 통합 테스트를 습관적으로 안 돌리게 되는
  실질 리스크(통합 게이트 도입 취지 무력화).

스레드 덤프로 근본 확인: Test worker 가 `DatabaseCleaner.clean()` → `TRUNCATE TABLE ...`에서
MySQL `waiting for handler commit`(InnoDB fsync) 대기. 데드락 아님, 순수 볼륨 비용.

### 관측된 낭비
`clean()`은 매 메서드마다 모든 테이블을 truncate하지만, 실제로 한 테스트가 건드리는 테이블은
소수다. 대부분의 테이블은 이미 "빈 상태 + AUTO_INCREMENT=1"(pristine)이며, 그런 테이블을
truncate하는 것은 **관측 가능한 상태 변화가 없는 no-op**이다(빈 것을 비우고, 1인 AI를 1로 리셋).

## 2. 목표 / 비목표

**목표**
- `clean()`이 **pristine이 아닌(더티) 테이블만 truncate** 하도록 변경 → fsync 횟수를 메서드당
  ~70에서 실제 건드린 소수(대개 0~5)로 축소.
- **동작(post-state)을 현행과 글자 그대로 동일하게 보존** — 어떤 테스트도 차이를 관측 불가.
- 로컬(Windows) 전량 IT 체감 속도 대폭 개선.

**비목표(이번 스펙 범위 아님)**
- Spring `@MockBean` 컨텍스트 파편화로 인한 컨텍스트 재빌드/클래스패스 스캔 비용(#2 슬로우니스).
  별도 과제. 이번 변경은 per-method TRUNCATE 비용(#1)만 다룬다.
- `@Transactional` 우선으로의 테스트 재분류(대안 B). 이번은 최소 침습(cleaner 내부만).
- PRESERVE_SET·격리 커넥션(C1)·quiescence(C2)·직렬 불변식 등 하네스의 다른 부분 변경.

## 3. 설계

### 3.1 "더티" 정의 (핵심 불변식)

현행 `clean()`의 사후 보장 = **비-PRESERVE 테이블 전부가 (0행 AND AUTO_INCREMENT=1)**.
(주의: 비-PRESERVE 테이블 중 일부는 Flyway 시드가 있다 — 예: `V5__create_administrator.sql`가
`user_account`/`administrator`/`member`를 시드하며 이들은 PRESERVE_SET이 아니다. 아래 논거는
"시드 없음"에 의존하지 않는다.)

무조건적 성립:

> 테이블이 **현재 (0행 AND AI=1)** 상태이면, truncate해도 지울 행이 없고 이미 1인 AI를 1로
> 리셋하는 것뿐 = **관측 가능한 상태 변화 없는 no-op → 스킵해도 사후 상태 동일 → 안전**.
> (그 테이블이 과거에 시드됐든, 쓰였다 정리됐든, 한 번도 안 쓰였든 무관 — 지금 상태만이 결정.)

시드된 비-PRESERVE 테이블(user_account 등)은 시드 행이 존재하므로 조건①(행 존재)로 더티 판정되어
정상적으로 truncate된다(현행과 동일). 즉 "시드 없음" 전제 없이도 정확하다.

**더티(= truncate 대상) ⟺ (행이 1개 이상 존재) OR (AUTO_INCREMENT > 1)**

- 조건①(행 존재)은 커밋된 잔여 데이터(오염원)를 잡는다.
- 조건②(AI>1)는 **"비었지만 AI가 전진한"** 케이스를 잡는다: `@Transactional` 테스트가 INSERT
  후 롤백하면 행은 사라지지만 InnoDB는 소비한 AUTO_INCREMENT를 되돌리지 않는다. INSERT 후
  자체 DELETE한 경우도 동일. 이들을 truncate해 AI=1로 복원 → 현행의 "AI 항상 1" 불변식 유지.

두 조건 모두 신뢰 가능한 판정이다(아래 3.2). 합집합은 "pristine이 아닌" 모든 테이블을 정확히
덮으므로, 이 집합만 truncate하면 사후 상태가 현행과 동일하다. **동작 불변, no-op만 제거.**

### 3.2 감지 (fsync 없는 읽기)

`clean()` 시작 시, 별도 autocommit 커넥션(현행과 동일)에서:

1. **행 존재 테이블 (조건①)** — 비-PRESERVE base table 목록에 대해 한 번의 배치 쿼리:
   ```sql
   SELECT 'tbl_a' AS t WHERE EXISTS (SELECT 1 FROM `tbl_a`)
   UNION ALL SELECT 'tbl_b' WHERE EXISTS (SELECT 1 FROM `tbl_b`)
   ... (비-PRESERVE 전 테이블)
   ```
   → 비어있지 않은 테이블 이름 집합(1 라운드트립). **실데이터 라이브 쿼리 = 캐시 없음.**
2. **AI 전진 테이블 (조건②)** — 한 번의 메타데이터 조회.
   ⚠️ **MySQL 8.0 캐시 우회 필수**: `information_schema.tables.AUTO_INCREMENT`(및 `TABLE_ROWS`)는
   8.0에서 **캐시된 통계**로, `information_schema_stats_expiry`(기본 86400초)에 지배된다. DML은
   이 캐시를 무효화하지 않으므로, 조회 전 세션 변수로 **캐시를 꺼서 live 값**을 읽어야 한다
   (안 그러면 INSERT-롤백 후에도 stale AI=1을 읽어 스킵 → 불변식 붕괴). 이 설정도 읽기라 fsync 무관.
   ```sql
   SET SESSION information_schema_stats_expiry = 0;
   SELECT table_name FROM information_schema.tables
   WHERE table_schema = DATABASE() AND auto_increment > 1;
   ```
   → AUTO_INCREMENT>1 테이블 집합(1 라운드트립). (AI 컬럼 없는 테이블은 `auto_increment` NULL →
   미포함. 그런 테이블은 조건①로만 판정 = 정확.)

   세션 변수는 pooled 커넥션에 남을 수 있으나 `stats_expiry=0`은 무해(메타 조회가 항상 fresh일 뿐)
   하며, 매 `clean()`이 재설정하므로 복원 불필요.

두 집합의 **합집합 ∩ (비-PRESERVE base table)** = truncate 대상.

감지는 전부 읽기(fsync 없음)이며 서버측에서 즉시 반환된다. 라운드트립은 TRUNCATE 1건의
fsync보다 훨씬 싸다. **비대칭 주의**: 조건①은 라이브 데이터 쿼리(캐시 무관), 조건②만 8.0 통계
캐시 우회(`stats_expiry=0`)가 필요하다.

### 3.3 실행 (현행과 동일한 뼈대)

```
try (Connection c = dataSource.getConnection()) {   // autocommit=true, 별도 커넥션 (C1)
    tables = queryBaseTables(c) 중 !PRESERVE
    dirty  = detectDirty(c, tables)                 // 3.2
    SET FOREIGN_KEY_CHECKS=0
    try { for t in dirty: TRUNCATE TABLE `t` }
    finally { SET FOREIGN_KEY_CHECKS=1 }             // 예외 경로 복원 (현행 유지)
}
```

- 별도 autocommit 커넥션·PRESERVE_SET·FK_CHECKS 복원 로직 전부 현행 그대로.
- `dirty`가 비어있으면 TRUNCATE 0건(대부분의 @Transactional 롤백 테스트).

### 3.4 정확성 논거 (요약)

- **핵심**: 현재 (0행 AND AI=1) 상태인 테이블은 truncate가 no-op → 스킵해도 사후 상태 동일.
  (시드 여부·과거 이력 무관, 현재 상태만이 결정 — §3.1.)
- 더티 정의가 "pristine 아님"을 **누락 없이** 덮음:
  행 존재(시드행 포함) / UPDATE(행 존재) / INSERT-롤백(0행·AI>1) / INSERT-DELETE(0행·AI>1) 전부 감지.
  단 조건②는 8.0 통계 캐시를 우회해야 live AI를 봄(§3.2) — 이 우회가 INSERT-롤백 케이스의 핵심.
- 따라서 `clean()` 사후 상태(모든 비-PRESERVE 테이블 = 0행 & AI=1)는 현행과 **동일**.
- FK: FK_CHECKS=0 하에 부분 집합 truncate는 dangling을 만들지 않음(현행과 동일).

## 4. 테스트 (TDD)

`DatabaseCleanerIsolationIT`(기존) 확장 — 별도 커밋의 검증:

- **T1 더티 정리**: 비-PRESERVE 테이블에 행 커밋 → `clean()` 후 0행.
- **T2 AI 전진 복원(같은 커넥션)**: 테이블에 INSERT 후 DELETE(행 0, AI>1) → `clean()` 후
  AI=1(다음 INSERT id=1).
- **T2b AI 전진 복원(INSERT-롤백, 캐시 버그 직격)**: 한 메서드에서 INSERT 후 롤백(행 0, engine AI 전진)
  → 다음 메서드 `clean()` → 그 다음 INSERT id=1 확인. **8.0 통계 캐시 우회(`stats_expiry=0`)가
  없으면 이 테스트가 실패**한다(회귀 가드). 반드시 메서드 경계를 넘겨 캐시 stale 경로를 태운다.
- **T3 pristine 스킵 후에도 격리 유지**: 기존 격리 검증(교차 테스트 오염 없음)이 그대로 그린.
- **T4 PRESERVE 불변**: 참조 시드(avatar/system_config) 보존 확인(기존 유지).
- **T5(화이트박스, 유지 권장)**: `clean()`이 pristine 테이블에 TRUNCATE를 발행하지 않음을 검증 —
  감지된 dirty 목록/카운터를 관측 지점으로 노출. false-negative 회귀를 잡는 가장 싼 가드라 유지한다.

## 5. 검증(게이트)

- `DatabaseCleanerIsolationIT` 그린(신규 T1~T4 포함).
- **전량 IT 2회 연속 그린**(로컬 배치 + CI) — 동작 불변이므로 기존 282 통과가 유지되어야 함.
- **CI 그린**(리눅스 단일호출) — 회귀 없음.
- **속도 측정**: 변경 전/후 로컬 배치(동일 클래스 세트)의 벽시계 비교, 개선 폭 기록.

## 6. 리스크 / 완화

- **감지 누락(false-negative)로 더티 테이블 스킵 → 격리 붕괴**: 가장 큰 위험. 완화 = 정확성
  논거(3.4) + T1~T3 + 전량 2회 그린. 조건①②가 신뢰 판정이라 논리적 누락 없음.
- **information_schema 캐시(8.0)**: MySQL 8.0에서 `AUTO_INCREMENT`·`TABLE_ROWS` **둘 다 캐시된
  통계**(`information_schema_stats_expiry` 기본 86400s, DML로 무효화 안 됨). 그래서 (a) 행 판정은
  캐시 없는 라이브 `EXISTS`로만 하고, (b) AI 판정은 조회 전 `SET SESSION
  information_schema_stats_expiry=0`으로 캐시를 꺼 live 값을 읽는다(§3.2). 이 우회를 빼면 stale
  AI=1로 INSERT-롤백 테이블을 스킵해 격리가 조용히 깨진다 — 이번 최적화의 최대 함정.
- **UNION 쿼리 길이(~70 테이블)**: 문자열이 길지만 단일 쿼리로 처리, 문제 없음. 테이블 목록은
  동적이라 하드코딩 없음.
- **롤백 불가**: 순수 성능 최적화, 스키마/마이그레이션 무변경.

## 7. 대안 (기각)

- **B: 트랜잭션 우선 재설계** — 롤백 가능 테스트를 @Transactional로, 커밋 필요 소수만 정리.
  더 표준적이나 각 테스트 재분류로 침습·리스크 큼. 이번 목표(로컬 속도) 대비 과함 → 후순위.
- **C: 빈 테이블도 AI 리셋(ALTER TABLE AUTO_INCREMENT=1)** — ALTER도 DDL이라 부분 fsync,
  이득 축소. 3.1의 "AI>1만 truncate"가 동일 효과를 더 싸게 달성.
- **A1: 빈 것 전부 스킵 + AI 리셋 포기** — 동작이 바뀌어(빈-AI-전진 테이블 미리셋) id=1 가정
  테스트를 깨뜨릴 위험. 3.1이 이 위험을 감지비용 거의 없이 제거하므로 불필요.
