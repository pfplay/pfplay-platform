# Test Speed Analysis

> ⚠️ **2026-03-15 시점 측정 기록이다** (확인: 2026-07-31). 이후 테스트 하네스가 통째로 바뀌었으므로
> (실 Flyway 스키마 기반 통합 테스트 분리 — [ADR 007](adr/007-integration-test-flyway-harness.md))
> 수치는 현재와 비교 불가하다. **측정 방법론만 재사용**한다.

## 측정 환경

| 항목 | 값 |
|------|-----|
| OS | Windows 11 Pro |
| CPU 코어 | 18 |
| JDK | Amazon Corretto 17.0.11 |
| Gradle | 빌드 캐시 + 데몬 + 병렬 빌드 활성화 |
| Spring Boot | 3.2.3 |
| 테스트 클래스 수 | 152개 (app 95, user 35, playlist 17, common 5) |

## 측정 결과

**전체 빌드 시간: ~2분** (`clean test --rerun` 기준)

### 모듈별 `:test` 태스크 소요 시간

| 모듈 | 테스트 클래스 수 | `:test` 시간 | 비고 |
|------|-----------------|-------------|------|
| **:app** | 95 | **1m 23s** | WebMvcTest 3종 + 순수 단위 테스트 |
| **:user** | 35 | **1m 26s** | WebMvcTest 1종 + 순수 단위 테스트 |
| **:playlist** | 17 | **1m 12s** | WebMvcTest 1종 + 순수 단위 테스트 |
| **:common** | 5 | **38s** | 순수 단위 테스트만 (Spring Context 없음) |

> 모듈 간 의존 순서(common → realtime/playlist/user → app)에 의해 common이 끝난 후 나머지가 실행된다.
> `org.gradle.parallel=true` 덕분에 playlist, user, app은 병렬로 실행되어 wall-clock 기준 ~2분.

### 느린 테스트 클래스 Top 10

| 시간 | 모듈 | 클래스 | 테스트 수 | 유형 |
|------|------|--------|----------|------|
| 21.7s | common | ExceptionCreatorTest | 5 | 순수 단위 |
| 15.3s | app | AdminDemoServiceTest | 4 | 순수 단위 |
| 6.7s | common | GlobalExceptionHandlerTest | 6 | 순수 단위 |
| 4.8s | playlist | PlaylistQueryControllerTest | 2 | WebMvcTest |
| 3.7s | playlist | PlaylistCommandControllerTest | 3 | WebMvcTest |
| 3.5s | user | AvatarResourceQueryServiceTest | 4 | 순수 단위 |
| 3.3s | playlist | TrackCommandControllerTest | 4 | WebMvcTest |
| 2.7s | user | UserAvatarCommandControllerTest | 2 | WebMvcTest |
| 2.6s | playlist | TrackCommandServiceTest | 20 | 순수 단위 |
| 2.4s | user | GuestSignServiceTest | 2 | 순수 단위 |

> "순수 단위"로 분류된 테스트가 수 초~20초 걸리는 것은 테스트 로직 자체가 아니라 **JVM fork의 첫 클래스 로딩 비용**이 포함되기 때문이다.

---

## 문제 원인

### 1. 과도한 JVM Fork — 17개 프로세스 생성

```groovy
// build.gradle (subprojects 블록)
maxParallelForks = Math.max(1, Runtime.runtime.availableProcessors().intdiv(4))
// → 18코어 / 4 = maxParallelForks = 4 (모듈당)
```

`maxParallelForks = 4`이면 Gradle이 모듈당 최대 4개의 **독립 JVM 프로세스**를 fork한다.
4개 모듈 × 최대 4 fork = **최대 16개 JVM**이 동시에 생성되며, 실측 17개가 관찰되었다.

**핵심 문제**: Spring의 ApplicationContext 캐시는 JVM 프로세스 내부에서만 작동한다.
fork가 늘어날수록 동일한 `@WebMvcTest` 설정이라도 **각 JVM마다 독립적으로 Spring Context를 로딩**한다.

예시: playlist 모듈(17개 테스트, `maxParallelForks=4`)
- fork 1: PlaylistQueryControllerTest → **Context 로딩 ~4.8s** + 테스트 실행
- fork 2: TrackCommandControllerTest → **동일 Context 재로딩 ~3.3s** + 테스트 실행
- fork 3: PlaylistCommandControllerTest → **동일 Context 재로딩 ~3.7s** + 테스트 실행
- fork 4: 나머지 테스트

모두 `AbstractPlaylistWebMvcTest`를 공유하지만, JVM이 다르므로 캐시가 작동하지 않는다.

### 2. Spring Context 설정이 8종으로 분산

모듈 전체에서 8개의 서로 다른 Spring ApplicationContext가 존재한다.

| # | 위치 | 유형 | Context 키를 결정하는 설정 |
|---|------|------|--------------------------|
| 1 | app | `@WebMvcTest` | AbstractAdminWebMvcTest — 3개 컨트롤러, MockBean 5개 |
| 2 | app | `@WebMvcTest` | AbstractPartyCommandWebMvcTest — 9개 컨트롤러, MockBean 9개 |
| 3 | app | `@WebMvcTest` | AbstractPartyQueryWebMvcTest — 7개 컨트롤러, MockBean 7개 |
| 4 | app | `@WebMvcTest` | AuthControllerTest — **독립 context**, 컨트롤러 1개 |
| 5 | app | `@WebMvcTest` | PartyroomAccessQueryControllerTest — **독립 context**, 커스텀 SecurityFilterChain |
| 6 | playlist | `@WebMvcTest` | AbstractPlaylistWebMvcTest — 5개 컨트롤러, MockBean 6개 |
| 7 | user | `@WebMvcTest` | AbstractUserWebMvcTest — 8개 컨트롤러, MockBean 12개 |
| 8 | app | `@SpringBootTest` | AbstractIntegrationTest — 전체 Context + Testcontainers |

**Context #4, #5**: 단 1개의 테스트 클래스를 위해 별도 Spring Context를 로딩한다.
이 두 개만으로 추가 ~3~5초가 낭비된다.

> 참고: **Context #8 (통합 테스트)**는 `@Tag("integration")`으로 기본 `test` 태스크에서 제외되어 있으므로
> 커밋 전 일반 테스트에는 영향 없음.

### 3. 순수 단위 테스트의 JVM 초기화 오버헤드

common 모듈의 5개 테스트는 `@ExtendWith(MockitoExtension.class)`만 사용하는 순수 단위 테스트다.
Spring Context를 로딩하지 않음에도 **합계 38초**가 소요된다.

원인:
- `maxParallelForks=4`에 의해 5개 테스트가 최대 4개 JVM에 분산 → **JVM 기동 비용 × 4**
- 각 JVM은 classpath에 Spring Boot + JPA + QueryDSL 등 전체 의존성을 로딩
- `common/build.gradle`의 `testImplementation 'spring-boot-starter-test'`로 인해 classpath가 무거움
- 첫 번째 테스트 클래스 실행 시 classpath scanning + class loading에 5~20초 소요

### 4. 모듈 간 순차 의존성

```
common (38s) → [완료 대기] → playlist (1m12s)
                            → user    (1m26s)  ← 병렬
                            → app     (1m23s)  ← 병렬
```

`org.gradle.parallel=true`로 모듈 간 병렬 실행은 활성화되어 있으나,
common이 완료되어야 나머지가 시작되므로 **최소 38초의 직렬 대기**가 존재한다.

---

## 시간 구성 분석 (추정)

전체 2분의 시간이 어디에 쓰이는지 분해한 추정치:

| 구간 | 시간 | 비율 |
|------|------|------|
| Gradle 기동 + 의존성 해석 | ~1s | 1% |
| 컴파일 (캐시 히트) | ~5s | 4% |
| **JVM fork 기동 (17개)** | **~50s** | **42%** |
| **Spring Context 로딩 (8종 × fork 중복)** | **~40s** | **34%** |
| 실제 테스트 로직 실행 | ~20s | 17% |
| 기타 (jar 패키징, I/O) | ~2s | 2% |

> **테스트 코드 자체의 실행 시간은 전체의 ~17%에 불과하다.**
> 나머지 83%는 JVM 기동, classpath 로딩, Spring Context 초기화에 소모된다.

---

## 해결 방안

### A. maxParallelForks 조정 — 즉시 적용 가능, 효과 높음

**현재**: `maxParallelForks = availableProcessors / 4 = 4`
**권장**: `maxParallelForks = 1`

```groovy
tasks.named('test') {
    maxParallelForks = 1
}
```

**이유**: fork가 1개이면 모든 테스트가 단일 JVM에서 실행되어:
- JVM 기동 비용: 17회 → 4회 (모듈당 1회)
- Spring Context 캐시가 JVM 내에서 완전히 재사용됨
- 동일 WebMvcTest 설정을 공유하는 테스트 클래스들이 context를 한 번만 로딩

**예상 효과**: JVM fork 기동 50초 → ~10초, Context 중복 로딩 40초 → ~15초
전체 시간: **~2분 → ~50초** (약 50% 단축 추정)

> `maxParallelForks > 1`이 유리한 경우는 테스트 수가 수백 개이고 개별 테스트가 CPU-bound로 오래 걸릴 때뿐이다.
> 이 프로젝트처럼 테스트 자체는 가볍고 context 로딩이 무거운 경우에는 fork를 줄이는 것이 유리하다.

### B. 독립 Context 통합 — 중간 노력, 효과 중간

`AuthControllerTest`와 `PartyroomAccessQueryControllerTest`가 각각 독립된 `@WebMvcTest`를 선언하여 별도 context를 생성한다.

**방안**: 이 두 테스트를 기존 abstract base에 통합하거나, 공통 abstract base를 만들어 context 수를 8 → 6으로 줄인다.

- `AuthControllerTest` → `AbstractAuthWebMvcTest` 분리 후 향후 auth 관련 웹 테스트를 추가할 기반으로 활용
- `PartyroomAccessQueryControllerTest` → `AbstractPartyQueryWebMvcTest`에 커스텀 SecurityFilterChain을 조건부로 적용

**예상 효과**: context 2개 제거 → ~3~5초 단축

### C. JVM 기동 최적화 — 즉시 적용 가능, 효과 중간

```groovy
tasks.named('test') {
    jvmArgs '-XX:+UseParallelGC',
            '-XX:TieredStopAtLevel=1',   // JIT 컴파일 최소화 (테스트는 장기 실행 아님)
            '-Xverify:none'               // 바이트코드 검증 생략 (테스트 환경 한정)
}
```

**예상 효과**: JVM 기동 시간 ~20% 단축

> 주의: `-Xverify:none`은 Java 13+에서 deprecated. `-XX:TieredStopAtLevel=1`은 안전하게 사용 가능.

### D. Spring Context 캐싱 극대화를 위한 테스트 실행 순서 제어

Gradle의 JUnit Platform에서는 테스트 클래스 실행 순서를 제어할 수 없으나,
`forkEvery = 0` (현재 설정)이 context 캐시를 보존하므로 이 설정은 유지해야 한다.

> `forkEvery > 0`으로 변경하면 N개 테스트마다 JVM을 재시작하여 context 캐시가 파괴된다. 절대 변경하지 말 것.

### E. 테스트 태그 기반 선택적 실행 — 장기 개선

커밋 시 전체 테스트 대신 변경된 모듈의 테스트만 실행:

```bash
# 예: playlist 모듈만 변경된 경우
./gradlew :playlist:test
```

또는 Gradle의 `--tests` 옵션으로 특정 패턴만 실행:

```bash
./gradlew :app:test --tests "*CommandServiceTest"
```

Git hook이나 CI 스크립트에서 `git diff --name-only`로 변경된 모듈을 감지하여 자동화할 수 있다.

### F. Gradle Build Cache 활용 극대화 — 이미 활성화됨

```properties
# gradle.properties (현재 설정)
org.gradle.caching=true
```

변경이 없는 모듈의 테스트는 캐시에서 `FROM-CACHE`/`UP-TO-DATE`로 스킵된다.
단, `clean test`를 실행하면 캐시가 무효화되므로 **`clean` 없이 `test`만 실행**하는 것이 좋다.

---

## 권장 적용 순서

| 순서 | 방안 | 노력 | 예상 단축 | 위험도 |
|------|------|------|----------|--------|
| 1 | **A. maxParallelForks=1** | 1줄 변경 | ~60초 (50%) | 낮음 |
| 2 | **C. JVM 기동 최적화** | 1줄 변경 | ~5~10초 | 낮음 |
| 3 | **B. 독립 Context 통합** | 테스트 리팩토링 | ~3~5초 | 중간 |
| 4 | **E. 선택적 실행** | 스크립트 작성 | 상황에 따라 | 낮음 |

---

## 적용 결과

### 적용한 방안: A + C

```groovy
// build.gradle — 변경 전
maxParallelForks = Math.max(1, Runtime.runtime.availableProcessors().intdiv(4))
jvmArgs '-XX:+UseParallelGC'

// build.gradle — 변경 후
maxParallelForks = 1  // single JVM to maximize Spring Context cache reuse
jvmArgs '-XX:+UseParallelGC', '-XX:TieredStopAtLevel=1'
```

> `-Xverify:none`은 Java 13+에서 deprecated이므로 적용하지 않았다.

### 적용하지 않은 방안: B (독립 Context 통합)

`AuthControllerTest`는 MockBean 구성이 기존 abstract base와 다르고,
`PartyroomAccessQueryControllerTest`는 커스텀 `SecurityFilterChain`(permitAll)이 필요하여
기존 base에 통합하면 다른 테스트의 보안 설정에 영향을 준다.

A 적용으로 fork가 모듈당 1개가 되면 context 캐시가 JVM 내에서 완전히 재사용되므로,
독립 context가 있더라도 **모듈당 1회만 로딩**된다. 무리한 통합 없이도 충분한 효과를 얻었다.

### Before / After 비교

| 지표 | Before | After | 변화 |
|------|--------|-------|------|
| **전체 빌드 시간** | **~2분** | **~32초** | **-73%** |
| JVM fork 수 | 17개 | 4개 (모듈당 1개) | -76% |
| Task Execution (합산) | 5m 50s | 1m 8s | -81% |

### 모듈별 `:test` 태스크 Before / After

| 모듈 | Before | After | 변화 |
|------|--------|-------|------|
| **:app** | 1m 23s | **25s** | -70% |
| **:user** | 1m 26s | **13s** | -85% |
| **:playlist** | 1m 12s | **12s** | -83% |
| **:common** | 38s | **5s** | -87% |
