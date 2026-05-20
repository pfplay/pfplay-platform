# E/#223 — 대기 중 DJ 플레이리스트 변경 API (Change Playlist) 설계

- 작성일: 2026-05-21
- 대상 이슈: pfplay-platform [E/#223](https://github.com/pfplay/pfplay-platform/issues/223) (로드맵 Cluster E, 신규 기능)
- 대상 레포: pfplay-platform (party 모듈, backend only). frontend 작업은 별 PR(pfplay-web)
- 분류: 신규 기능(feature gap) — 도메인 mutator + 신규 PATCH endpoint
- 심각도: Feature (Cluster A Critical 후순위)

## 1. 배경 / 문제

DJ 대기열에 등록된 사용자가 **대기 중에 디제잉 플레이리스트를 변경**할 방법이 없다.

현행(2026-05-21 코드 확인):
- `DjCommandController` endpoint 3개만: `POST .../dj-queue`(enqueue, playlist 고정) · `DELETE .../dj-queue/me`(self dequeue) · `DELETE .../dj-queue/{djId}`(강제 dequeue).
- `DjData.playlistId` 는 `@Embedded`, `create()` 시 1회 설정, 이후 mutator 없음(`updateOrderNumber` 만 존재).
- 우회 = dequeue 후 재 enqueue → **큐 맨 뒤로 밀림**(`enqueueDj` 의 `nextOrder = queuedDjs.size() + 1`) = 순서 손실, 사실상 변경 불가.
- pfplay-web `widgets/partyroom-djing-dialog/ui/body.component.tsx:147` 에 `<Button>{t.playlist.btn.change_playlist}` 가 이미 placeholder(`alert('Not Impl')`)로 존재 = 프론트는 진입 UI 준비됨, backend API 부재가 유일한 차단.

부수로 확인된 동일 도메인 invariant 누락:
- `DjCommandService.enqueueDj` 는 `playlistQueryPort.isEmptyPlaylist` 만 호출, **playlist 소유권 검증 없음** = 타인 소유 playlist 로도 enqueue 가능(현 frontend picker 가 본인 playlist 만 노출해서 실사용 노출은 낮으나 boundary 책임 누락). 본 신규 PATCH 와 함께 동반 보강.

## 2. 결정 (사용자 확정)

1. **허용 범위 = 대기 중 DJ만**. 재생 중 DJ 의 playlist 재지정은 **out-of-scope**(별건 후속 결정).
2. **WS 통지 불필요 = REST 200 응답만**. 근거: `DjWithProfileDto` payload 가 `crewId, orderNumber, nickname, avatarIconUri` 만 — playlist 정보 없음, DJ 큐 모달이 다른 크루의 playlist 를 노출하지 않음 = 본인만 결과 확인하면 됨. broadcast 발행 시 no-op 메시지가 됨.
3. **빈 playlist = 거부**. `DjEnqueueSpecification` 의 `EMPTY_PLAYLIST` (DJ-003) 정합 유지. 동작 의미 = "빈 playlist 로 DJ 할 수 없다" 도메인 invariant.
4. **playlist 소유권 검증 = PATCH + enqueue 동반 보강**. 기존 enqueue 의 누락 invariant 를 같은 PR 에서 함께 잠금(brainstorming skill 지침: "include targeted improvements as part of the design" — 동일 도메인 boundary).

부수 결정(설계 단계 권장):
- **응답 = 204 No Content** (dequeue-me 와 정합, REST 정합).
- **Idempotency = 같은 playlistId 로 PATCH 시 204** (REST PATCH idempotent semantic — 도메인 mutator 호출은 발생하나 JPA dirty check 로 no-op).
- **DjChangeType enum 무변** (CHANGE_PLAYLIST 추가 안 함) — WS broadcast 발행하지 않으므로 enum 신설 불요.

## 3. 설계

### 3-1. API 계약

```
PATCH /api/v1/partyrooms/{partyroomId}/dj-queue/me
Auth: cookieAuth, @PreAuthorize("hasRole('MEMBER')")
Headers: Content-Type: application/json
Body:   { "playlistId": <Long, required, >0> }
Success: 204 No Content
```

**Error 매핑** (HTTP / errorCode / 사유):

| HTTP | errorCode | 메시지 키 | 사유 |
|---|---|---|---|
| 400 | INVALID_REQUEST | (validator) | `playlistId` null / non-positive |
| 401 | (AuthEntryPoint) | — | 인증 결여 |
| 403 | DJ-002 QUEUE_CLOSED | (재사용) | DJ 큐 closed 상태 |
| 403 | DJ-003 EMPTY_PLAYLIST | (재사용) | 빈 playlist |
| 403 | **DJ-005 NOT_OWNED_PLAYLIST** (신규) | "본인 소유 플레이리스트가 아닙니다" | playlist 소유자 ≠ 요청자 |
| 404 | DJ-004 NOT_FOUND_DJ | (재사용) | 요청자가 DJ 큐에 없음 |
| 409 | **DJ-006 CURRENT_DJ_CANNOT_CHANGE_PLAYLIST** (신규) | "재생 중 DJ는 플레이리스트를 변경할 수 없습니다" | playback 활성 + `isCurrentDj(crewId)` = true |

**OpenAPI**: `@ApiErrorCodes({DjException.class})` 데코레이터 부착.

### 3-2. 도메인 변경 (party 모듈)

**`DjData`** (entity/data/DjData.java)
```java
public void updatePlaylist(PlaylistId playlistId) {
    this.playlistId = playlistId;
}
```
- `orderNumber` 무변 (큐 순서 보존)
- `equals/hashCode` 무변 (id 기반)
- `@DynamicUpdate` 이미 적용 → playlist_id 컬럼만 UPDATE 발행

**`DjException`** 신규 항목 2:
```java
NOT_OWNED_PLAYLIST("DJ-005", "본인 소유 플레이리스트가 아닙니다", ErrorType.FORBIDDEN),
CURRENT_DJ_CANNOT_CHANGE_PLAYLIST("DJ-006", "재생 중 DJ는 플레이리스트를 변경할 수 없습니다", ErrorType.CONFLICT),
```

**`DjChangePlaylistSpecification`** (신규, domain/specification)
```java
public class DjChangePlaylistSpecification {
    public void validate(DjQueueData djQueue, boolean isCurrentDj,
                         boolean isOwned, boolean isEmptyPlaylist) {
        djQueue.validateOpen();                                      // DJ-002
        if (isCurrentDj)     throw create(CURRENT_DJ_CANNOT_CHANGE); // DJ-006
        if (!isOwned)        throw create(NOT_OWNED_PLAYLIST);       // DJ-005
        if (isEmptyPlaylist) throw create(EMPTY_PLAYLIST);           // DJ-003
    }
}
```
- 평가 순서 근거: queue closed → 메타 조건(current dj) → 소유권(보안) → 콘텐츠(empty). 보안 게이트가 콘텐츠 게이트보다 앞.

**`DjEnqueueSpecification`** (동반 보강 — **breaking signature change: arity 3 → 4**)
```java
// before: validate(DjQueueData djQueue, boolean isAlreadyRegistered, boolean isEmptyPlaylist)
// after:
public void validate(DjQueueData djQueue, boolean isAlreadyRegistered,
                     boolean isOwned, boolean isEmptyPlaylist) {
    djQueue.validateOpen();
    if (!isOwned)        throw create(NOT_OWNED_PLAYLIST);    // 신규 DJ-005
    if (isEmptyPlaylist) throw create(EMPTY_PLAYLIST);
    if (isAlreadyRegistered) throw create(ALREADY_REGISTERED);
}
```
- 호출자(`DjCommandService.enqueueDj`)에서 `isOwned` 계산값 전달. 프로덕션 caller 1개(grep 확인). 단, 기존 `DjEnqueueSpecificationTest` 가 직접 호출 → **테스트 픽스처/시그니처 sweep 필수**(compile-driven, plan task 명시).
- 평가 순서: ownership → empty → duplicate (보안 우선).
- **Contract 변경 (OpenAPI 클라이언트 영향)**: 평가 순서 swap 결과 — *기존*: 이미 등록된 사용자 + 타인 playlist 시도 → `DJ-001 ALREADY_REGISTERED`. *변경 후*: 같은 케이스 → `DJ-005 NOT_OWNED_PLAYLIST` 가 먼저. 현 frontend picker 가 본인 playlist 만 노출하므로 실 노출 경로 없음, 다만 OpenAPI 스펙을 코드 외부에서 소비하는 클라이언트가 있다면 에러 코드 우선순위 변경에 유의.

### 3-3. Port 확장

**party 모듈 `PlaylistQueryPort`** (`app/.../party/application/port/out/PlaylistQueryPort.java`)
```java
public interface PlaylistQueryPort {
    boolean isEmptyPlaylist(Long playlistId);
    boolean isOwnedBy(Long playlistId, Long userId);  // 신규
}
```

**party 모듈 `PlaylistQueryAdapter`** (`app/.../party/adapter/out/external/PlaylistQueryAdapter.java`) — 현행 `isEmptyPlaylist` 가 `TrackQueryService.isEmptyPlaylist` 에 위임하는 것과 같은 패턴으로 `isOwnedBy` 는 playlist 모듈의 `PlaylistQueryService.isOwnedBy(Long, UserId)` 신규 메서드에 위임. UserId VO wrap:
```java
@Override
public boolean isOwnedBy(Long playlistId, Long userId) {
    return playlistQueryService.isOwnedBy(playlistId, new UserId(userId));
}
```

**playlist 모듈 신규 메서드**: `PlaylistQueryService.isOwnedBy(Long playlistId, UserId userId): boolean` — 기존 `findByIdAndUserId(playlistId, userId)` 가 `null` 반환 시 false, non-null 시 true (또는 동등 효율의 exists 쿼리). 구현 위치는 plan 단계에서 결정(현 서비스 메서드 재사용 vs `PlaylistQueryPort.existsByIdAndUserId` 신설).

**Non-existent playlist semantic**: `isOwnedBy` 가 playlist 미존재 시 `false` 반환 → `NOT_OWNED_PLAYLIST` (DJ-005) 매핑. 별도 NOT_FOUND 분기를 안 두는 이유 = (1) playlist 정상 존재 여부는 caller 책임 분리, (2) security boundary 응답으로 enumeration 회피, (3) frontend picker 가 본인 playlist 만 노출하므로 사용자 노출 경로 없음. **회귀 가드 테스트 버킷 필수**: §4-2/§4-4 에 non-existent playlistId → DJ-005 case 명시.

### 3-4. 서비스 계층

**`DjCommandService.changePlaylist`** (신규 메서드)

```java
@Transactional
public void changePlaylist(PartyroomId partyroomId, PlaylistId newPlaylistId) {
    AuthContext authContext = ThreadLocalContext.getAuthContext();
    Long userId = authContext.getUserId().getUid();

    log.info("[changePlaylist] ENTER requestId={} partyroomId={} userId={} newPlaylistId={}",
        RequestIdInterceptor.current(), partyroomId.getId(), userId, newPlaylistId.getId());

    PartyroomData partyroom         = partyroomQueryService.getPartyroomById(partyroomId);
    PartyroomPlaybackData playback  = aggregatePort.findPlaybackState(partyroomId);
    DjQueueData djQueue             = aggregatePort.findDjQueueState(partyroomId);
    CrewData crew                   = partyroomQueryService.getCrewOrThrow(partyroomId, authContext.getUserId());
    CrewId crewId                   = new CrewId(crew.getId());

    DjData me = aggregatePort.findDj(partyroomId, crewId)                       // 기존 port 메서드 재사용
        .orElseThrow(() -> ExceptionCreator.create(DjException.NOT_FOUND_DJ));   // DJ-004

    boolean isCurrentDj      = playback.isActivated() && playback.isCurrentDj(crewId);
    boolean isOwned          = playlistQueryPort.isOwnedBy(newPlaylistId.getId(), userId);
    boolean isEmptyPlaylist  = playlistQueryPort.isEmptyPlaylist(newPlaylistId.getId());

    new DjChangePlaylistSpecification().validate(djQueue, isCurrentDj, isOwned, isEmptyPlaylist);

    Long oldPlaylistId = me.getPlaylistId() != null ? me.getPlaylistId().getId() : null;
    me.updatePlaylist(newPlaylistId);
    // ※ saveDj 명시 호출 안 함 — me 는 @Transactional 컨텍스트의 managed entity,
    //   JPA dirty check 가 commit 시 UPDATE 자동 발행([[feedback_elegant_no_code_dirtying]])

    log.info("[changePlaylist] OK requestId={} partyroomId={} crewId={} oldPlaylistId={} newPlaylistId={}",
        RequestIdInterceptor.current(), partyroomId.getId(), crewId.getId(), oldPlaylistId, newPlaylistId.getId());
    // 도메인 이벤트 발행 없음 — WS broadcast 불필요(§2 결정 2)
}
```

**동시성 / 레이스 윈도우 (수용 정책)**: 본 메서드는 `@Transactional` 컨텍스트 안에서 `me` 를 load → mutate → commit 한다. 같은 사용자의 dequeue 와 changePlaylist 가 동시 도착하는 사용자 정상 시나리오는 [[single-partyroom-subscription-invariant]] 상 동일 디바이스 = 단일 세션이므로 사실상 직렬화. 그러나 admin 강제 dequeue 또는 백엔드 cron(presence grace) 이 동일 row 를 동시 변경하는 윈도우는 존재 — 이 경우 changePlaylist tx commit 이 already-deleted row 에 UPDATE 발행 → 0 rows affected silently. **수용 정책 = last-writer-wins, JPA 의 dirty-update 가 silent no-op 처리. `@Version` / pessimistic lock 미도입**(트레이드오프: 정상 경로 99.9% 시나리오 단순화 우선, 비정상 race 는 ⇒ 사용자가 다시 enqueue + change 로 회복 가능). 동일하게 `djQueue.validateOpen` snapshot 후 다른 tx 가 close 하는 race 도 수용. §6 위험 목록에 명시.

**enqueue 동반 보강**:
```java
boolean isOwned = playlistQueryPort.isOwnedBy(playlistId.getId(), authContext.getUserId().getUid());
boolean isEmptyPlaylist = playlistQueryPort.isEmptyPlaylist(playlistId.getId());
new DjEnqueueSpecification().validate(djQueue, isAlreadyRegistered, isOwned, isEmptyPlaylist);
```
- 호출 위치는 기존 `validate(djQueue, isAlreadyRegistered, isEmptyPlaylist)` 직전 `isEmptyPlaylist` 계산 라인 옆.

### 3-5. Repository / Aggregate

`PartyroomAggregatePort.findDj(PartyroomId, CrewId): Optional<DjData>` **이미 존재** (`app/.../party/domain/port/PartyroomAggregatePort.java:42`). 신설 불요 — `changePlaylist` 가 이를 그대로 호출. (spec 초안의 `findDjByPartyroomAndCrew` 신설 제안은 reviewer 가 중복 지적 → 폐기, [[feedback_elegant_no_code_dirtying]] 정합.)

### 3-6. Controller

**`DjCommandController.changePlaylist`** (신규)
```java
@Operation(summary = "본인 DJ 플레이리스트 변경",
           description = "DJ 대기열에 등록된 본인의 디제잉 플레이리스트를 변경합니다. 재생 중 DJ는 변경할 수 없습니다.")
@ApiResponse(responseCode = "204", description = "변경 성공")
@SecurityRequirement(name = "cookieAuth")
@ApiErrorCodes({DjException.class})
@PatchMapping("/{partyroomId}/dj-queue/me")
@PreAuthorize("hasRole('MEMBER')")
public ResponseEntity<Void> changePlaylist(
        @Parameter(description = "파티룸 ID") @PathVariable Long partyroomId,
        @Valid @RequestBody ChangePlaylistRequest request) {
    djCommandService.changePlaylist(new PartyroomId(partyroomId), new PlaylistId(request.getPlaylistId()));
    return ResponseEntity.noContent().build();
}
```

**`ChangePlaylistRequest`** (신규 DTO, web/payload/request/dj)
```java
public record ChangePlaylistRequest(@NotNull @Positive Long playlistId) {}
```

## 4. 테스트 전략

`pfplay-platform` 단일 PR. test 분포는 [[feedback_pr_series_workflow]] 정합.

### 4-1. Specification Unit
**`DjChangePlaylistSpecificationTest`** (신규, 5+)
- happy: queue open / not current / owned / non-empty → no throw
- queue closed → QUEUE_CLOSED
- isCurrentDj → CURRENT_DJ_CANNOT_CHANGE_PLAYLIST
- !isOwned → NOT_OWNED_PLAYLIST
- isEmpty → EMPTY_PLAYLIST
- 평가 우선순위 잠금: queue-closed + not-owned 동시 → QUEUE_CLOSED 가 먼저

**`DjEnqueueSpecificationTest`** (확장, 2 추가)
- !isOwned → NOT_OWNED_PLAYLIST
- !isOwned + isEmpty → NOT_OWNED_PLAYLIST 가 먼저(보안 우선 순서 잠금)

### 4-2. Service Unit
**`DjCommandServiceChangePlaylistTest`** (또는 기존 `DjCommandServiceTest` 확장, 7+)
- happy: `me.updatePlaylist(newId)` 호출 + `eventPublisher.publishEvent` 0회 (broadcast 없음). `saveDj` 어서션 안 함 (dirty-check 의존)
- not-in-queue: NOT_FOUND_DJ throw
- current DJ: DJ-006 throw
- not owned: DJ-005 throw
- non-existent playlistId (isOwnedBy=false 모킹) → DJ-005 throw  ← 신규 (§3-3 semantic 잠금)
- empty playlist: DJ-003 throw
- idempotent (same playlistId): `updatePlaylist(sameId)` 호출되더라도 예외 없음. SQL UPDATE 발행 여부는 IT(§4-3) 책임

**`DjCommandServiceEnqueueTest`** (회귀 가드 보강, 2 추가)
- enqueue + not-owned playlist → DJ-005
- 기존 happy 픽스처에 `playlistQueryPort.isOwnedBy = true` mock 보정(전 픽스처 sweep)

### 4-3. IT (party 모듈)
**`DjCommandIntegrationTest.changePlaylist`** (3)
- happy: enqueue → changePlaylist → DB 재조회 시 playlist_id 갱신, order_number 보존
- invariant: enqueue 2명 → 2번째가 changePlaylist → 다른 DJ 의 order_number 무변
- idempotent: enqueue → 같은 playlistId 로 changePlaylist → DB 재조회 시 playlist_id 무변, no logical change (SQL UPDATE 발행 여부는 dirty-check 거동상 환경 의존 — assert는 "최종 row 상태 무변"으로)

### 4-4. Controller WebMvc
**`DjCommandControllerTest.changePlaylist`** (신규, 7+)
- 204 happy
- 400 invalid body (playlistId null / 0 / negative)
- 401 unauth
- 404 NOT_FOUND_DJ (DJ-004)
- 403 DJ-003 EMPTY_PLAYLIST  ← 별도 case
- 403 DJ-005 NOT_OWNED_PLAYLIST  ← 별도 case (non-existent playlistId 포함)
- 409 DJ-006 CURRENT_DJ_CANNOT_CHANGE_PLAYLIST

### 4-5. 회귀 가드 (cross-cutting)
- `:app:test` 전체 GREEN
- `:app:integrationTest` 전체 GREEN — [[reference_mysql_datetime0_rounding]] 위생 정책 적용(필요 시 @AfterEach native delete cleanup)

## 5. 영향 / Out-of-scope

### 영향 (in-scope)
- party 모듈:
  - **신규**: `DjChangePlaylistSpecification`, `ChangePlaylistRequest` DTO
  - **확장**: `DjData.updatePlaylist` mutator, `DjException` (DJ-005/006), party `PlaylistQueryPort.isOwnedBy`, party `PlaylistQueryAdapter` 위임 추가, `DjCommandService.changePlaylist` + `enqueueDj` ownership 추가, `DjEnqueueSpecification.validate` arity 3→4, `DjCommandController.changePlaylist` endpoint
  - **무변**: `PartyroomAggregatePort.findDj` 기존 메서드 재사용
- playlist 모듈: `PlaylistQueryService.isOwnedBy(Long, UserId)` 신규 (또는 동등). 기존 `findByIdAndUserId` 재사용 형태 가능
- OpenAPI doc: 신규 endpoint + DJ-005/006 항목 자동 반영(`@ApiErrorCodes` 데코)

### Out-of-scope (별건 후속)
- **재생 중 DJ 의 playlist 재지정** (사용자 결정 §2-1) — UX 요구 발생 시 별 spec. **forward link**: 그 시점엔 다음 트랙부터 적용 vs 즉시 적용 결정 + 다른 크루 화면 갱신 필요성 = WS broadcast (`CHANGE_PLAYLIST` enum 신설) 가 거의 확실히 필요해질 것. 즉 본 spec 의 "no broadcast" 결정은 **"대기 중 DJ 만"** 범위로 명시적 한정.
- **frontend 진입** (`pfplay-web/widgets/partyroom-djing-dialog/ui/body.component.tsx:147` `alert('Not Impl')` 제거 + playlist picker + PATCH 호출 + error 토스트) — pfplay-web 별 PR
- **WS broadcast 확장** (`CHANGE_PLAYLIST` enum) — `DjWithProfileDto` 에 playlist 메타 노출 디자인이 합의되면 그때 진입(위 forward link 와 결합 가능)
- **기존 enqueue 의 ownership 위반 데이터 백필** — DB audit 필요 시 별건

## 6. 잠재 위험 / 검증 포인트

- **party `PlaylistQueryPort.isOwnedBy` 어댑터 위치**: party 모듈의 `PlaylistQueryAdapter` 가 playlist 모듈 `PlaylistQueryService.isOwnedBy(Long, UserId)` 신규 메서드에 위임. 기존 `isEmptyPlaylist → TrackQueryService.isEmptyPlaylist` 의 패턴 미러. plan 단계에서 playlist 모듈 메서드의 구현 형태(`findByIdAndUserId null 체크 재사용` vs `exists 쿼리 신설`) 결정.
- **`PartyroomAggregatePort.findDj` 재사용 (신설 회피)**: 기존 시그니처 `findDj(PartyroomId, CrewId): Optional<DjData>` 활용 — reviewer 가 중복 검출, 신설 폐기. [[feedback_elegant_no_code_dirtying]] 정합.
- **enqueue 회귀 영향**: 기존 enqueue 테스트 픽스처에서 `PlaylistQueryPort.isOwnedBy` mock 누락 시 전부 DJ-005 회귀. 픽스처 sweep 명시(plan task). `DjEnqueueSpecification.validate` arity 변경(3→4)으로 인한 compile-driven sweep 도 동반.
- **enqueue 에러 코드 우선순위 변경 (contract change)**: 평가 순서 swap 결과, 기등록+타인 playlist 동시 위반 시 *전*: DJ-001 ALREADY_REGISTERED → *후*: DJ-005 NOT_OWNED_PLAYLIST. 사용자 노출 경로는 picker 가 본인 playlist 만 노출하므로 0 이나, OpenAPI 외부 클라이언트 가능성을 위해 명시적 인지. (§3-2 참조)
- **`saveDj` 명시 호출 제거 → JPA dirty check 의존**: `me` 가 managed entity 이므로 `@Transactional` commit 시 자동 UPDATE 발행. enqueue 패턴(`aggregatePort.saveDj` 명시 호출, 새 entity 라 `persist` 의도)와 비대칭이지만, 업데이트 시 명시 save 는 redundant noise. service unit test 에서는 saveDj 호출 횟수 어서션 안 함.
- **Idempotent semantic 의 JPA 동작**: 같은 playlist_id 로 `updatePlaylist` → dirty check 가 변경 없음 인지 → SQL UPDATE 미발행이 일반적이나 `@DynamicUpdate` 와의 결합·연관 컬럼 변화 등 환경 의존. IT 단계 어서션은 "최종 row 상태 무변" 으로 한정(SQL UPDATE 발행 0회 어서션은 brittle 회피).
- **동시성 race 윈도우 (수용)**: §3-4 에 명시한 admin-dequeue / cron / queue-close 의 동시 발생 시 silent no-op 가능. 회복 = 사용자 재 enqueue + change. `@Version`/pessimistic lock 미도입(YAGNI vs 실제 고통: 현 시점 보고된 race incident 0).
- **ownership 누락 historical data**: 백필 안 함(별건). 신규 enqueue/PATCH 만 invariant 잠금 — 기존 DJ row 의 historical playlist 가 ownership 위반 상태라도 시스템 동작에 영향 없음(read-only 인용만 됨).

## 7. 머지·배포 순서

[[reference_branch_env_mapping]] develop=dev / release=stg / main=prod.

1. backend PR → develop merge → deploy-dev workflow 트리거 (사용자 영역 = 머지)
2. dev 환경 smoke (PATCH 204 / 에러 코드 6종 / 회귀 enqueue)
3. release(stg) 격상 PR — 별건, 사용자 영역
4. prod 승격 — [[feedback_main_squash_merge]] squash, 사용자 영역

## 8. 관련

- 이슈: [pfplay-platform#223](https://github.com/pfplay/pfplay-platform/issues/223)
- 로드맵: `bugs/2026-05-14-bug-fix-roadmap.md` Cluster E row E/#223
- 인접 작업: E/#3(2026-05-19, doStart DJ별 playable 스캔으로 빈 playlist 자동 skip — 본 spec 의 §2-3 결정 정합), E/#222(2026-05-18, skip→reorder invariant 회귀잠금 — 같은 도메인)
- 메모리: [[feedback_elegant_no_code_dirtying]], [[feedback_pr_series_workflow]], [[feedback_autonomous_execution]], [[feedback_korean_issue_commit_pr]]
