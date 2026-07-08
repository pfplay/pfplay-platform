# playback/dj reconcile cron (Cluster G 잔존 오염 자가치유)

- 날짜: 2026-06-19
- 범위: pfplay-platform (party BC)
- 이슈: platform #308 · Cluster G(#241 인접) · 포렌식 #299(stuck)·#304(orphan)
- 상태: 설계 승인됨 (구현 전)

## 1. 배경 / 문제

party 도메인에는 presence에만 자가치유 안전망(`PartyroomPresenceService.reconcileStalePending`, 60s cron)이 있고, **playback/dj에는 대응 안전망이 없다**. 예방 fix(#300 deactivate 증발, #305 비활성 크루 enqueue 차단)는 **새 오염만 막을 뿐 이미 생긴 오염을 retroactive하게 치유하지 못한다**. 두 잔존-오염 클래스가 prod에 방치된다:

- **A. orphan DJ (#304)** — `dj` row의 crew가 `is_active=false`. 회전 유령(오늘 룸1에서 목격). 비활성 크루의 트랙이 계속 회전 재생.
- **B. stuck playback (#299/#195)** — `partyroom_playback.is_activated=1`인데 현재 트랙 `end_time`이 한참 과거(타이머 사망·빈 큐). 정지 유령 + tar pit(새 DJ enqueue도 start 안 됨).

(forensic: `bugs/2026-06-12-...`(#299) "playback reconcile = 궁극 방어", `bugs/2026-06-18-...`(#304) "dj.crew inactive reconcile".)

## 2. 목표 / 비목표

**목표**
- 두 잔존-오염 클래스를 **주기적으로(~60s) 자가치유** — presence reconcile의 playback/dj 대칭.
- **건강한 룸을 절대 손상시키지 않는다**(정상 로테이션과 레이스 회피).

**비목표**
- 예방(이미 #300/#305) 재구현. refresh(#306-①)·WS 만료검증(#306-②).

## 3. 설계

신규 `PartyroomPlaybackReconcileService`(presence reconcile 미러). `@Scheduled(fixedDelay=60_000)` `reconcile()`가 두 sweep을 순차 실행. 각 sweep은 **룸별 분산락 + 룸별 트랜잭션 + txn 내 조건 재검(멱등)**.

### 3.1 Sweep A — orphan DJ
- **탐지(신규 쿼리)**: `DjData.crewId`는 임베디드 `CrewId`(스칼라 long 아님)이고 `DjData`↔`CrewData` JPA 연관 없음 → 임베디드 경로 `.id`로 비교, 교차조인:
  `SELECT DISTINCT d.partyroomId FROM DjData d, CrewData c, PartyroomData pr WHERE c.id = d.crewId.id AND c.isActive = false AND pr.partyroomId = d.partyroomId AND pr.status = ACTIVE` → 반환 타입 **`List<PartyroomId>`**(VO, `List<Long>` 아님).
- **치유**(룸 락 안, 멱등): 그 룸의 dj 중 crew가 inactive인 행을 `removeDjs`로 제거. 제거 대상에 **현재 DJ(`current_dj_crew_id`)가 포함됐고 `is_activated`** 면 `skipPlayback`(=cancelTask + tryProceed → 남은 실 DJ 회전 or 빈 큐 deactivate). 현재 DJ가 아니면 제거만(현재 재생 무중단). txn 내 재검(이미 정리됐으면 no-op).

### 3.2 Sweep B — stuck playback
- **탐지(신규 쿼리)**: `currentPlaybackId`도 임베디드 VO → `.id` 비교. **LEFT JOIN**으로 "is_activated=true인데 current_playback이 null/부재"(별개 오염 클래스)도 stuck으로 포함:
  `SELECT pp.partyroomId FROM PartyroomPlaybackData pp, PartyroomData pr LEFT JOIN PlaybackData p ON p.id = pp.currentPlaybackId.id WHERE pr.partyroomId = pp.partyroomId AND pr.status = ACTIVE AND pp.isActivated = true AND (p IS NULL OR p.endTime < :threshold)`.
  `threshold = nowEpochMillis − BUFFER_MS`. **`endTime`은 epoch-millis(Long, UTC `Instant.toEpochMilli`)** → `threshold = System.currentTimeMillis() - 90_000L`(명시적 millis, s/ms 혼동 차단).
- **치유**(룸 락 안, 멱등): `skipPlayback`(cancelTask로 잔존 타이머 정리 + tryProceed → 다음 DJ 회전 or 빈 큐 deactivate) → 정지 해소. txn 내 재검(여전히 stuck인지 — 그 사이 정상 complete가 치유했으면 skip).

### 3.3 안전 (핵심)
- **generous BUFFER = 90초(ms)**: 정상 타이머 발화 지연(Redis pub/sub·GC) + reconcile 간격을 넘는 마진 → 정상 로테이션 룸을 절대 안 건드림. (worst-case 치유 지연 ≈ BUFFER + 60s ≈ 2.5분.)
- **룸별 분산락** `DistributedLockExecutor`, key=`playback-reconcile:<roomId>` — **중복 cron tick(멀티 인스턴스) 차단 전용.** ⚠️ 정상 playback 커맨드(`complete`/`skipByManager`/`skipPlayback`)는 **분산락을 안 씀**(단일 Redis expiration task+DB txn 의존) → 이 락은 *정상 커맨드와 상호배제하지 않는다.* 정상-path vs reconcile 레이스 방어는 **BUFFER + in-txn 멱등 재검**이 담당.
- **멱등 재검(락+txn 안)**: 탐지 목록을 신뢰하지 말고, **`performTaskWithLock` supplier 내부 + `@Transactional` 경계 안**에서 조건을 재조회·재확인(presence `forceOffline`가 락 안에서 DB 재읽기 하는 것과 동일). 탐지~락획득 사이 정상 로테이션이 완료됐으면 no-op.
- **트랜잭션 구조**: sweep 루프 메서드는 `@Transactional` **금지**(한 룸 실패가 배치 전체 롤백 방지). **룸별 heal 메서드만 `@Transactional`**(presence 구조 그대로).
- **락 획득 실패**: WARN만 남기고 미실행(재시도·예외 없음) → 60s마다 재실행이라 안전(liveness).
- **sweep 로깅**: 무엇을(룸·dj·playback id·전이) 쓸었는지 `log.info`(트러블슈팅 자산).
- 치유는 기존 `skipPlayback`/`removeDjs` 재사용 → 새 mutation 로직 최소, broadcast(활성 룸 화면 갱신)는 기존 경로가 처리.

### 3.4 결정 (승인됨)
- stuck 치유에 **DJ 포인트 미부여**(`complete` 아닌 `skipPlayback`): 장시간 정지된 트랙의 DJ는 이미 떠났으므로 retroactive 포인트 부적절 → advance/deactivate만.
- BUFFER = 90초.

## 4. 컴포넌트
- 신규 `PartyroomPlaybackReconcileService` — cron + `sweepOrphanDjs()` + `sweepStuckPlayback()`.
- 신규 쿼리 2개: `DjRepository.findPartyroomIdsWithInactiveCrewDj()`, `PartyroomPlaybackRepository.findStuckActivatedPartyroomIds(threshold)` (+ aggregatePort 노출).
- 재사용: `PlaybackControlPort.skipPlayback`, `PartyroomAggregateService.removeDjs`/`removeDjFromQueue`, `DistributedLockExecutor`, `findDjsOrdered`/`findCrewById`/`findPlaybackState`.

## 5. 데이터 흐름
```
@Scheduled(60s) reconcile()
  ├─ sweepOrphanDjs():
  │     rooms = findPartyroomIdsWithInactiveCrewDj()
  │     for room: lock(playback-reconcile:room) → tx:
  │        inactive dj 재조회 → removeDjs → (현재DJ 포함 & activated면) skipPlayback
  └─ sweepStuckPlayback():
        rooms = findStuckActivatedPartyroomIds(now-90s)
        for room: lock(playback-reconcile:room) → tx:
           재검(여전히 stuck) → skipPlayback
```

## 6. 엣지 케이스
- 한 룸이 두 클래스 동시: A 먼저(orphan 제거+skip)가 stuck도 해소 → B는 재검 no-op. 순서 무해.
- 정상 로테이션 직후(end_time 막 지남, BUFFER 내): B 탐지 제외 → 미손상.
- orphan이 현재 DJ 아님: 제거만, 현재 재생 무중단.
- 락 경합/획득 실패: presence 패턴대로 WARN 로깅 후 다음 tick에 재시도(잡 자체는 멱등).
- 빈 룸(active crew 0): 치유 후 deactivate, broadcast 생략 무해.
- **TERMINATED/SUSPENDED 룸**: 두 탐지 쿼리에 `PartyroomData`를 조인해 **`status = ACTIVE`만** 대상으로(죽은 룸에서 heal·이벤트 발행 노이즈 방지). TERMINATED row는 존재하므로 `getPartyroomById`는 안 던짐 → 명시적 status 필터 필요.
- **is_activated=true ∧ current_playback null**: Sweep B의 LEFT JOIN `p IS NULL` 분기로 stuck 처리(skipPlayback→빈 큐면 deactivate).

## 7. 테스트
- **탐지 쿼리 IT**: orphan dj 선별(active crew의 dj 제외) / stuck 선별(BUFFER 내 트랙 제외·is_activated=false 제외·**activated+null playback 포함**·**TERMINATED 룸 제외**).
- **치유 단위**: orphan=현재DJ → removeDjs+skipPlayback / orphan=비현재 → 제거만, skip 미호출 / stuck → skipPlayback / 멱등(조건 미충족 룸 → 아무 mutation 없음).
- **안전 회귀**: BUFFER 내 정상 재생 룸·TERMINATED 룸은 sweep 대상 아님.

## 8. 미해결 / 후속
- prod 1회성 복구(이미 방치된 룸)는 cron이 첫 tick에 자동 처리 → 별도 SQL 불요(단 배포 직후 첫 sweep 로그 확인 권장).
- 멀티 인스턴스 시 cron 중복 실행은 룸별 분산락이 흡수(presence와 동일).
