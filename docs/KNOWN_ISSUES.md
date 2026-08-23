# Known Issues & Traps

> **SoT 는 GitHub 이슈 트래커다.** 이 문서는 이슈 목록의 복사본이 아니라,
> **코드를 읽어도 잘 안 보이는데 밟으면 시간을 크게 잃는 함정**을 모아둔 곳이다.
> 각 항목은 "왜 그런가 / 어떻게 피하나 / 근거 파일"까지만 적는다.
> 최종 갱신: 2026-07-31.

---

## A. 설계상 알려진 갭 (이슈로 추적 중)

| 갭 | 요약 | 이슈 |
|---|---|---|
| 토큰 만료 graceful 재인증 부재 | 사용자 세션은 24h 단발이고 refresh·슬라이딩 갱신이 없다. 만료 후 HTTP 는 401, 이미 맺힌 WS 는 핸드셰이크 시점 신원으로 계속 동작 → split-brain | [#306](https://github.com/pfplay/pfplay-platform/issues/306) |
| 배포 readiness 오판 | `scripts/deploy.sh` 는 readiness 타임아웃에도 `exit 0`(WARN) → 진짜 부팅 실패가 배포 성공으로 보고됨 | [#342](https://github.com/pfplay/pfplay-platform/issues/342) |
| 탈퇴 닉네임 영구 결번 | 탈퇴는 email 만 익명화하고 `user_profile.nickname` 은 남는다. UNIQUE 제약 때문에 앱 레벨 필터로는 못 푼다 — 익명화만이 해법 | [#339](https://github.com/pfplay/pfplay-platform/issues/339) |
| enum 영속화 4원화 | ORDINAL 암묵 사용이 섞여 있어 enum 상수 순서를 바꾸면 조용히 오염된다 | [#333](https://github.com/pfplay/pfplay-platform/issues/333) |
| 앱 로그 Cloud Logging 미수집 | VM stdout → Cloud Logging 경로가 끊겨 사후 조사에 앱 로그를 못 쓴다 | [#275](https://github.com/pfplay/pfplay-platform/issues/275) |
| Presence ↔ AccessCommand 순환 | `PartyroomPresenceService` 가 `PartyroomAccessCommandService` 를 직접 주입한다(이벤트화 미적용) | [#228](https://github.com/pfplay/pfplay-platform/issues/228) |

---

## B. 인프라·런타임 함정

### B-1. Redis 만료 이벤트는 유실된다 — 타이머 하나에 크론 하나

`__keyevent@*__:expired` 는 fire-and-forget 이다. 앱이 안 붙어 있는 순간에 키가 만료되면 그
알림은 **영구히 사라진다**(Redis 는 버퍼링하지 않는다). 그래서 시간 기반 동작에는 반드시
주기 reconciler 가 뒤에 있어야 한다.

- 재생 진행: `PartyroomPlaybackReconcileService`(60초) — `is_activated=1` 인데 `end_time` 이
  90초 이상 지난 룸을 `skipPlayback` 으로 회수
- presence: `reconcileStalePending`(60초) + liveness 스윕(5분)
- **새로운 만료-키 기능을 추가한다면 reconciler 도 같이 추가하는 것이 이 레포의 규칙이다.**

근거: `PartyroomPlaybackReconcileService`, `PlaybackDurationWaitTopicListener`,
[ADR 008](adr/008-self-healing-reconcile-cron.md).

### B-2. 로컬 컨테이너는 "호스트에서 빌드된 jar" 를 담는다

`app/Dockerfile` 은 `COPY app/build/libs/*.jar app.jar` 다. 즉 **소스를 고쳐도
`./gradlew :app:bootJar` 를 안 하면 이전 빌드가 그대로 뜬다.** 컨테이너를 다시 올렸는데 수정이
반영 안 된 것처럼 보이는 대부분의 사례가 이것이다.

### B-3. `up -d app` 은 pytube 사이드카를 안 띄운다

compose 의 `app` 서비스 `depends_on` 은 mysql·redis 뿐이다. `up -d app` 만 하면 `pytube` 는
내려가 있고, 음악 검색만 실패한다(다른 기능은 정상이라 원인 파악이 늦어진다).

### B-4. Actuator 가 없다

의존성 자체가 없다. `/actuator/health` 로 살아있음을 확인하려는 스크립트는 전부 오작동한다.
"어떤 HTTP 응답이든 오면 부팅 완료" 가 이 레포의 판정 방식이다(`scripts/deploy.sh` 동일).

### B-5. 컨테이너 로그 시각 = KST, `--since` = UTC

`app/Dockerfile` 이 `TZ=Asia/Seoul` 이라 로그 타임스탬프는 KST 로 찍히지만
`docker logs --since` 에 넣는 값은 UTC 로 해석된다. 9시간 어긋난 창을 조회하고
"로그가 없다" 고 오판하기 쉽다.

---

## C. 영속성·마이그레이션 함정

### C-1. 스키마 주인은 Flyway, JPA 는 `validate`

전 프로필이 `ddl-auto: validate` 다. 엔티티만 고치고 마이그레이션을 빼먹으면
**부팅이 실패한다**(원하는 동작이다 — 조용한 드리프트보다 낫다).
반대로 테스트에서 `create-drop` 을 쓰면 이 드리프트가 가려지므로, 통합 테스트도 실 Flyway 로
부팅한다([ADR 007](adr/007-integration-test-flyway-harness.md)).

### C-2. Flyway 버전 슬롯 충돌 — 배치 머지 직전에 확인할 것

여러 브랜치가 각자 다음 번호를 선점하면 머지 후 같은 `V{n}` 이 둘 생긴다(과거 V30 충돌 →
재번호 필요). 대량 머지 전에 한 번 확인하는 습관이 싸다.

```bash
ls app/src/main/resources/db/migration | sed -E 's/^(V[0-9]+)__.*/\1/' | sort | uniq -d
```

### C-3. `@Modifying` 은 `flushAutomatically` 와 세트로

`clearAutomatically = true` 만 걸면 벌크 쿼리 실행 시 **아직 flush 되지 않은 엔티티 변경이 조용히
폐기**된다. 이 레포는 항상 `@Modifying(clearAutomatically = true, flushAutomatically = true)` 로
쌍을 맞춘다(`CrewRepository`, `PushSubscriptionRepository`). 새 벌크 쿼리를 추가할 때도 유지할 것.

### C-4. 64비트 ID 를 JSON 숫자로 내리지 말 것

TSID(`hypersistence-tsid`) 기반 `userId` 는 2^53 을 넘는다. JSON 숫자로 직렬화하면 JS 클라이언트에서
정밀도가 깎여 **엉뚱한 대상에 mutation 이 나간다**(어드민 봇 관리 전면 오작동 사례, PR #345).
클라이언트로 나가는 64비트 식별자는 문자열로 직렬화한다.

---

## D. 트랜잭션·이벤트 함정

### D-1. `AFTER_COMMIT` 리스너에서 쓰려면 `REQUIRES_NEW`

`@TransactionalEventListener(AFTER_COMMIT)` 시점엔 원 트랜잭션이 이미 커밋돼 있다. 그 컨텍스트로
저장을 시도하면 아무것도 flush 되지 않는다. 쓰기가 필요하면
`@Transactional(propagation = REQUIRES_NEW)` 를 명시한다(`PartyroomActivityListener` 참조).

### D-2. 롤백-only 오염 회피용 별 트랜잭션

유니크 위반 등으로 outer 트랜잭션이 rollback-only 가 된 뒤에는 같은 트랜잭션에서 조회조차
정상 처리되지 않는다. 승자 조회 같은 후속 읽기는 `REQUIRES_NEW` 로 분리한다
(`PartyroomAccessCommandService` 의 `requiresNewReadOnlyTx`).

---

## E. 테스트 함정

### E-1. 통합 테스트는 병렬화하면 서로를 파괴한다

IT 는 **단일 공유 Testcontainers 스키마**를 `DatabaseCleaner` 로 테스트마다 truncate 한다.
병렬 fork 는 남의 스키마를 지우므로 `maxParallelForks = 1` 이 불변식이다(build.gradle 주석 참조).
스키마-per-fork 격리를 도입하기 전에는 병렬화를 시도하지 말 것.

### E-2. 로컬 게이트는 `:app:test` 전체로

ArchUnit 규칙이 `:app:test` 에 있다. 좁은 태스크만 돌리면 경계 위반이 CI 까지 살아 넘어간다.

### E-3. 부팅에서만 잡히는 버그가 있다

JPQL 오타·잘못된 경로 참조는 단위 테스트를 통과하고 **부팅 시점에** 터진다.
dev 머지 전에 fresh DB 로 한 번 풀부팅 하는 것이 가장 싼 방어다.

---

## F. 최근 해소된 항목 (기록)

| 과거 이슈 | 해소 |
|---|---|
| WS 재연결 시 세션 캐시 미저장 → 채팅 등 후속 기능 실패 | `tryEnter` 응답의 `reactivated` 신호(PR #307) + 웹 재연결 resync(web PR #430)로 종결 |
| 앱 다운타임 중 만료 이벤트 유실로 재생 고착 | 자가치유 크론(PR #309)으로 종결 — [#195](https://github.com/pfplay/pfplay-platform/issues/195) |
| 비정상 급사 시 유령 online 잔존 | presence liveness 스윕(PR #357)으로 종결 — [#241](https://github.com/pfplay/pfplay-platform/issues/241) |
| 유저당 활성 파티룸 다중화(wedge) | V38 유니크 인덱스로 DB 불변식화(PR #350) |
| `crew_count` 캐시 카운터 드리프트 | 라이브 COUNT 전환 후 컬럼 제거(V39, PR #366) |

---

**관련 문서**: [`OPERATIONS.md`](OPERATIONS.md) · [`CONTEXT_MAP.md`](CONTEXT_MAP.md) · [`adr/`](adr/)
