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

**`DjEnqueueSpecification`** (동반 보강)
```java
public void validate(DjQueueData djQueue, boolean isAlreadyRegistered,
                     boolean isOwned, boolean isEmptyPlaylist) {
    djQueue.validateOpen();
    if (!isOwned)        throw create(NOT_OWNED_PLAYLIST);    // 신규 DJ-005
    if (isEmptyPlaylist) throw create(EMPTY_PLAYLIST);
    if (isAlreadyRegistered) throw create(ALREADY_REGISTERED);
}
```
- 호출자(`DjCommandService.enqueueDj`)에서 `isOwned` 계산값 전달. signature 확장이지만 같은 클래스 1 caller(서비스) → 폭발 반경 작음.
- 평가 순서: ownership → empty → duplicate (보안 우선).

### 3-3. Port 확장

**`PlaylistQueryPort`** (party/application/port/out)
```java
public interface PlaylistQueryPort {
    boolean isEmptyPlaylist(Long playlistId);
    boolean isOwnedBy(Long playlistId, Long userId);  // 신규
}
```
구현 = playlist 모듈의 기존 어댑터(`PlaylistQueryAdapter` 또는 동등). 단순 owner_user_id 비교 쿼리. playlist 자체 미존재 시 `false` 반환(NOT_OWNED_PLAYLIST 매핑) — 별도 NOT_FOUND 분기를 안 두는 이유 = playlist 정상 존재 여부는 caller 책임 분리, security boundary 응답으로 enumeration 회피.

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

    DjData me = aggregatePort.findDjByPartyroomAndCrew(partyroomId, crewId)
        .orElseThrow(() -> ExceptionCreator.create(DjException.NOT_FOUND_DJ));   // DJ-004

    boolean isCurrentDj      = playback.isActivated() && playback.isCurrentDj(crewId);
    boolean isOwned          = playlistQueryPort.isOwnedBy(newPlaylistId.getId(), userId);
    boolean isEmptyPlaylist  = playlistQueryPort.isEmptyPlaylist(newPlaylistId.getId());

    new DjChangePlaylistSpecification().validate(djQueue, isCurrentDj, isOwned, isEmptyPlaylist);

    Long oldPlaylistId = me.getPlaylistId() != null ? me.getPlaylistId().getId() : null;
    me.updatePlaylist(newPlaylistId);
    aggregatePort.saveDj(me);

    log.info("[changePlaylist] OK requestId={} partyroomId={} crewId={} oldPlaylistId={} newPlaylistId={}",
        RequestIdInterceptor.current(), partyroomId.getId(), crewId.getId(), oldPlaylistId, newPlaylistId.getId());
    // 도메인 이벤트 발행 없음 — WS broadcast 불필요(§2 결정 2)
}
```

**enqueue 동반 보강**:
```java
boolean isOwned = playlistQueryPort.isOwnedBy(playlistId.getId(), authContext.getUserId().getUid());
boolean isEmptyPlaylist = playlistQueryPort.isEmptyPlaylist(playlistId.getId());
new DjEnqueueSpecification().validate(djQueue, isAlreadyRegistered, isOwned, isEmptyPlaylist);
```
- 호출 위치는 기존 `validate(djQueue, isAlreadyRegistered, isEmptyPlaylist)` 직전 `isEmptyPlaylist` 계산 라인 옆.

### 3-5. Repository / Aggregate 확장

**`PartyroomAggregatePort.findDjByPartyroomAndCrew`** (신규)
```java
Optional<DjData> findDjByPartyroomAndCrew(PartyroomId partyroomId, CrewId crewId);
```
- 구현(`PartyroomAggregateAdapter`): JPA repository 단건 조회. 기존 `findDjsOrdered(partyroomId)` 필터링은 큐 전체 로드 → 비효율.
- 새 JPA repository 메서드 시그니처: `Optional<DjData> findByPartyroomIdAndCrewId(PartyroomId, CrewId)` (둘 다 `@Embedded` value object).

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
**`DjCommandServiceChangePlaylistTest`** (또는 기존 `DjCommandServiceTest` 확장, 6+)
- happy: updatePlaylist 호출 + saveDj 1회 + 이벤트 publish 0회(broadcast 없음)
- not-in-queue: NOT_FOUND_DJ, save 0회
- current DJ: DJ-006, save 0회
- not owned: DJ-005, save 0회
- empty playlist: DJ-003, save 0회
- idempotent: 같은 playlistId 입력 → updatePlaylist 호출되더라도 (JPA dirty check 가 no-op 처리) 예외 없음, 204 응답

**`DjCommandServiceEnqueueTest`** (회귀 가드 보강, 2 추가)
- enqueue + not-owned playlist → DJ-005
- 기존 happy 픽스처에 `playlistQueryPort.isOwnedBy = true` mock 보정(전 픽스처 sweep)

### 4-3. IT (party 모듈)
**`DjCommandIntegrationTest.changePlaylist`** (2)
- happy: enqueue → changePlaylist → DB 재조회 시 playlist_id 갱신, order_number 보존
- invariant: enqueue(orderNumber=2) → changePlaylist → 다른 DJ 의 orderNumber 무변

### 4-4. Controller WebMvc
**`DjCommandControllerTest.changePlaylist`** (신규, 6+)
- 204 happy
- 400 invalid body (playlistId null / 0 / negative)
- 401 unauth
- 404 NOT_FOUND_DJ
- 403 DJ-003 / DJ-005
- 409 DJ-006

### 4-5. 회귀 가드 (cross-cutting)
- `:app:test` 전체 GREEN
- `:app:integrationTest` 전체 GREEN — [[reference_mysql_datetime0_rounding]] 위생 정책 적용(필요 시 @AfterEach native delete cleanup)

## 5. 영향 / Out-of-scope

### 영향 (in-scope)
- party 모듈: `DjData` / `DjException` / `DjCommandService` / `DjCommandController` / `DjEnqueueSpecification` / `DjChangePlaylistSpecification`(신규) / `PlaylistQueryPort`(확장) / `PartyroomAggregatePort`(확장) / `ChangePlaylistRequest`(신규)
- playlist 모듈: `PlaylistQueryAdapter`(또는 동등) — `isOwnedBy` 구현 추가
- OpenAPI doc: 신규 endpoint + DJ-005/006 항목 자동 반영(`@ApiErrorCodes` 데코)

### Out-of-scope (별건 후속)
- **재생 중 DJ 의 playlist 재지정** (사용자 결정 §2-1) — UX 요구 발생 시 별 spec
- **frontend 진입** (`pfplay-web/widgets/partyroom-djing-dialog/ui/body.component.tsx:147` `alert('Not Impl')` 제거 + playlist picker + PATCH 호출 + error 토스트) — pfplay-web 별 PR
- **WS broadcast 확장** (`CHANGE_PLAYLIST` enum) — `DjWithProfileDto` 에 playlist 메타 노출 디자인이 합의되면 그때 진입
- **기존 enqueue 의 ownership 위반 데이터 백필** — DB audit 필요 시 별건

## 6. 잠재 위험 / 검증 포인트

- **`PlaylistQueryPort.isOwnedBy` 어댑터 구현 위치**: playlist 모듈 어댑터에 추가. 다른 호출자 영향 없음(현재 isEmptyPlaylist 1개 메서드 인터페이스, 다른 도메인 무사용 — spec 작성 시점 확인됨).
- **`PartyroomAggregatePort.findDjByPartyroomAndCrew` 신설 vs `findDjsOrdered` 필터링**: 단건 쿼리 권장 — N+1 회피, 의도 명시. 기존 `findDjsOrdered` 의 caller (enqueue/dequeue/admin-dequeue/E/#3 doStart) 무변.
- **enqueue 회귀 영향**: 기존 enqueue 테스트 픽스처에서 `PlaylistQueryPort.isOwnedBy` mock 누락 시 전부 DJ-005 회귀. 픽스처 sweep 명시(plan task).
- **Idempotent semantic 의 JPA 동작**: 같은 playlist_id 로 `updatePlaylist` → `@DynamicUpdate` + dirty check 가 변경 없음 인지 → SQL UPDATE 미발행. 예외 발생 없음(원자 동작). IT 로 검증.
- **ownership 누락 데이터의 dev/stg 마이그레이션**: 백필 안 함(별건). 신규 enqueue/PATCH 만 잠금 — 기존 DJ row 의 historical playlist 가 ownership 위반 상태라도 시스템 동작에 영향 없음(read-only 인용만 됨).

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
