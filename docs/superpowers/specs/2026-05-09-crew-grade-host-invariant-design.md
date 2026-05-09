# Crew Grade Host Invariant — Design

- **Date**: 2026-05-09
- **Branch**: `fix/crew-grade-host-invariant`
- **Type**: Bug fix (display correctness)
- **Affected module**: `app/src/main/java/com/pfplaybackend/api/party/application/service/PartyroomAccessCommandService.java`

## 1. Problem

Super admin (V5-seeded `user_id=1`)은 Main Stage partyroom의 `host_id`로 설정되어 있다. 그러나 admin 콘솔 → SharedToken → `pfplay.xyz` Main Stage 진입 후 Grade 탭을 확인하면 **LISTENER**로 표시된다. Host인데 LISTENER로 분류되는 명백한 버그.

## 2. Root cause

세 요소가 결합:

1. **`PartyroomCommandService.createMainStage` (line 44-50)** 가 의도적으로 `enterByHost()`를 건너뜀.
   - 코멘트의 근거: "V5-seeded super-admin은 profile이 없으므로 enterByHost가 NPE; host_id로 충분"
   - 결과: Main Stage 생성 시 `partyroom.host_id`만 세팅, **HOST `CrewData` row는 생성되지 않음**
2. **`PartyroomAccessCommandService.tryEnter` → `ensureCrewActive`** 가 신규 CrewData를 항상 `GradeType.LISTENER`로 INSERT (`:145`). host 여부를 검사하지 않음.
3. **`PartyroomSetupQueryService.getCrewsForSetup`** (Grade 탭 데이터 source) 가 오직 `CrewData.gradeType`만 읽고 `partyroom.hostId`를 참조하지 않음 (`partyview` 패키지 grep 결과 0건).

추가로 stale assumption: `ApplicationReadyEventListener` step 2 (`:39`)에서 `superAdminSeedService.finalizeSuperAdminProfile()`이 추가되어 super admin이 profile을 갖게 되었지만, `createMainStage`의 코멘트와 호출 흐름은 업데이트되지 않음. 또한 admin 콘솔이 2026-05-09에 prod 첫 진입하며 super admin이 customer 플로우(`tryEnter`)로 Main Stage에 들어가는 경로가 처음 열림 → 이전엔 가려져 있던 버그 노출.

## 3. Decision

**Fix B — `tryEnter`에서 host invariant 강제 (런타임 보장)**

선택 사유:
- 단일 source of truth: "`userId == partyroom.hostId` ⇒ `CrewData.gradeType == HOST`"라는 invariant를 코드 레벨에서 강제
- 기존 prod의 잘못된 LISTENER row가 다음 진입 1회로 자동 healing → 수동 SQL 마이그레이션 불필요
- dev/stg의 DB reset 정책과 자연스럽게 맞물림 — reset 후 super admin 첫 진입 시 즉시 HOST 등록
- 미래에 `enterByHost`를 거치지 않는 신규 host 등록 경로가 추가되어도 자동 방어

**대안 (반려)**:
- Fix A (`createMainStage`에서 `enterByHost` 호출): 대칭적이지만 prod 기존 LISTENER row를 자동 healing 못 함 (Main Stage seed는 `findByLinkDomain` 가드로 early-return), 추가로 super admin이 Main Stage에 상시 active crew로 표시됨 → UX 영향
- 수동 SQL only: 환경마다 (prod/stg/dev) 반복, dev/stg reset 마다 또 반복 → 운영 부담

## 4. Implementation

### 4.1 Helper

`PartyroomAccessCommandService`에 private 메서드 추가:

```java
/**
 * Host invariant 강제: 진입 user가 partyroom host인데 grade가 HOST가 아니면 승격.
 * Idempotent — 이미 HOST면 no-op. createMainStage가 enterByHost를 건너뛰는 경우와
 * 기존 잘못된 grade row를 자동 healing.
 */
private void enforceHostInvariant(PartyroomData partyroom, UserId userId, CrewData crew) {
    if (!userId.equals(partyroom.getHostId())) return;
    if (crew.getGradeType() == GradeType.HOST) return;
    GradeType prev = crew.getGradeType();
    crew.updateGrade(GradeType.HOST);
    aggregatePort.saveCrew(crew);
    log.info("[enforceHostInvariant] HEALED - userId={}, partyroomId={}, crewId={}, {} → HOST",
            userId, partyroom.getPartyroomId().getId(), crew.getId(), prev);
}
```

### 4.2 Call sites — `tryEnter` 두 군데

**(1) 같은-룸 재진입 분기 (현재 line 96-103)**
```java
} else {
    log.info("[tryEnter] Same room re-entry — countryCode 갱신만, no ENTER publish. ...");
    CrewData crew = existingCrew.orElseThrow(() ->
            ExceptionCreator.create(CrewException.INVALID_ACTIVE_ROOM));
    crew.updateCountryCode(countryCode);
    CrewData saved = aggregatePort.saveCrew(crew);
    enforceHostInvariant(partyroom, userId, saved);   // ADD
    return saved;
}
```

**(2) `ensureCrewActive` 결과 받은 후 (현재 line 107 이후)**
```java
CrewActivationResult result = ensureCrewActive(partyroom, userId, countryCode);
if (result.transitioned) {
    publishAccessChangedEvent(partyroom.getPartyroomId(), result.crew, userId);
} else {
    log.info("[tryEnter] IDEMPOTENT - already active or concurrent insert loser, no event. ...");
}
enforceHostInvariant(partyroom, userId, result.crew);  // ADD
return result.crew;
```

이 두 호출로 `tryEnter`의 모든 반환 경로 (분기 a/b/c/d)를 커버.

### 4.3 What we are NOT doing

1. **`createMainStage` 호출 흐름 변경 안 함** — invariant가 런타임에 보장되므로 동작상 동일. 단, stale 코멘트 한 줄 추가하여 future reader 안내 (host grade는 `enforceHostInvariant`가 보장)
2. **`countryCode` 정리 안 함** — frontend 미사용은 별도 cleanup PR로 추적
3. **`CrewGradeChangedEvent` 발행 안 함** — silent healing 채택. listener 사이드 이펙트 제로, 다음 setup 조회 시 반영
4. **수동 SQL 마이그레이션 만들지 않음** — fix 적용 후 다음 진입 1회로 자동 healing
5. **다른 grade 자가치유 안 함** (예: COMMUNITY_MANAGER) — partyroom 레벨에서 grade source는 host_id 외 없음
6. **`enterByHost` / `exit` / `expel` 등 다른 진입/이탈 경로 미수정** — 범위 밖, 각각 이미 올바르게 동작

## 5. DB / runtime cost

- **추가 SELECT: 0건.** `partyroom.hostId`(eager `@Embedded`)와 `crew.gradeType` 모두 기존 흐름이 이미 로드함
- **추가 메모리 비교**: `userId.equals(partyroom.getHostId())` + enum 비교 = 호출당 2회 in-memory. 측정 불가능한 수준
- **추가 UPDATE**: healing이 실제 필요할 때만 1회. `@DynamicUpdate` (CrewData class-level)로 grade_type 컬럼만 포함. healing은 사실상 1회성 (per super admin × 환경) 이라 누적 부담 무시 가능
- **로그**: healing 발생 시 INFO 1줄. 빈도 동일 (1회성)

## 6. Test plan

`PartyroomAccessCommandServiceTest` (기존 파일)에 7개 케이스 추가:

| # | 테스트 | 시나리오 | 검증 |
|---|--------|---------|------|
| 1 | `tryEnter_freshHostEntry_assignsHostGrade` | row 0건, userId == hostId, 첫 진입 | 새 CrewData가 `HOST` grade로 생성 (또는 LISTENER로 생성된 직후 즉시 HOST로 승격) |
| 2 | `tryEnter_freshNonHostEntry_assignsListenerGrade` | row 0건, userId != hostId, 첫 진입 | 새 CrewData가 `LISTENER` grade로 생성 (회귀 방지) |
| 3 | `tryEnter_existingListenerHost_promotesToHost` | row 있음 (LISTENER inactive), userId == hostId, 재진입 (b/c 분기) | grade `HOST`로 승격, saveCrew 호출됨 |
| 4 | `tryEnter_existingListenerNonHost_unchanged` | row 있음 (LISTENER), userId != hostId | grade `LISTENER` 유지, updateGrade 호출 0회 (잘못된 승격 방지) |
| 5 | `tryEnter_existingHostHost_idempotent` | row 있음 (HOST), userId == hostId | updateGrade/saveCrew **healing path** 호출 0회 |
| 6 | `tryEnter_sameRoomReentry_promotesHostIfStale` | 같은-룸 재진입 분기 (d), 기존 grade LISTENER, userId == hostId | grade HOST로 승격 |
| 7 | `tryEnter_healing_doesNotPublishGradeChangeEvent` | healing 발생 | `CrewGradeChangedEvent` publishEvent 호출 0회 (silent healing 검증) |

기존 회귀 보호:
- `PartyroomAccessCommandServiceTest` ENTER 이벤트 발행 케이스
- `PartyroomAccessCommandServiceDjQueueChangeTest`
- `CrewDataGradeTest`

## 7. Rollout / verification

- 코드 수정 + 테스트 통과 → develop merge
- prod 배포 후 verification:
  1. admin 콘솔 SharedToken 재발급
  2. pfplay.xyz Main Stage 진입
  3. Grade 탭 → HOST 표시 확인
  4. 백엔드 로그에 `[enforceHostInvariant] HEALED - userId=1, ...` 1회 INFO 기록 확인
- DB 확인 (옵션): `SELECT grade_type FROM crew WHERE user_id=1 AND partyroom_id=(SELECT partyroom_id FROM partyroom WHERE link_domain='main')` → `0` (HOST ordinal)

## 8. References

- `PartyroomAccessCommandService.java:62-117` — `tryEnter` 진입 흐름
- `PartyroomAccessCommandService.java:131-160` — `ensureCrewActive` 분기
- `PartyroomCommandService.java:44-50` — Main Stage 생성 (host crew 생성 안 함)
- `PartyroomCommandService.java:53-70` — 일반 partyroom 생성 (`enterByHost` 호출, 비교용)
- `ApplicationReadyEventListener.java:29-50` — super admin seed + Main Stage init
- `CrewData.java:151-153` — `updateGrade` 도메인 메서드
- `GradeType.java` — enum 선언 순서 (HOST=ordinal 0, LISTENER=ordinal 4)
- `partyview/application/dto/CrewSetupDto.java:28` — Grade 탭 데이터 source
- `app/src/main/resources/db/migration/V1__init_schema.sql:30` — `crew.grade_type tinyint`
