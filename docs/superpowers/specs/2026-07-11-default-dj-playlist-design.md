# 신규 가입자 기본 플레이리스트 — 설계 (#329)

## 배경 (재검증으로 정정된 문제 정의)

DJ 등록에는 "곡이 담긴 플레이리스트"가 필요하다. 원래 프레이밍은 "플리가 없어 생성 단계가 강요된다"였으나, 재검증 결과(2026-07-11):

- 가입 시 GRABLIST("그랩한 곡")가 항상 자동 생성되므로 회원의 플리 목록은 절대 0개가 아니다. 실제 게이트는 **"곡 있는 플리 없음"**이다 — 데스크탑 셀렉터의 `musicCount>0` 필터(`use-select-playlist.hook.tsx:95`)와 백엔드 `DjEnqueueSpecification`의 `EMPTY_PLAYLIST`(DJ-003) 거부.
- 따라서 이 기능이 제거하는 마찰은 정확히 **"플레이리스트 생성"이라는 조작 단계 하나**다. 곡 추가는 본질적으로 사용자 몫(취향)으로 남는다. 신규 유저는 DJ 등록 진입 시 플리가 이미 있으므로 **곡 추가부터 시작**하게 된다 — 모바일은 빈 플리 카드 "+곡 추가" CTA 기존재, 데스크탑 드로어도 플리 존재 시 생성 없이 곡 추가만 남는다. **웹 코드 무변경.**

## 사용자 확정 결정 (2026-07-11)

1. **신규 가입자만**: 가입 시 GRABLIST + **PLAYLIST 타입 기본 플리 각 1개**. 기존 유저 데이터 무변경(백필 없음) — 기존 유저는 혜택 없음(트레이드오프 수용).
2. **AM=1 상한 유지, 기본 플리 카운트 포함**: 정책·스키마 무변경. 신규 AM은 기본 플리 rename(`PATCH /api/v1/playlists/{id}` 기존재)으로 기존 AM(직접 1개 생성)과 실질 동등.
3. **기본 플리 이름 = "내 플레이리스트"** (서버 하드코딩 한국어 — GRABLIST "그랩한 곡" 선례와 동일, 유저 rename 가능).
4. **삭제 허용** (아래 삭제 시맨틱).
5. **GRABLIST 디제잉 = 의도된 제품 동작** (사용자 확인 2026-07-11): DJ enqueue가 플리 타입을 검사하지 않는 것은 정상이며, 그랩곡 보유 유저는 GRABLIST로 바로 디제잉 가능. 본 건에서 어떤 타입 가드도 추가하지 않는다.

## 목표 / 성공 기준

- 신규 가입 직후 회원의 플리 상태 = GRABLIST + "내 플레이리스트" 정확히 각 1개.
- 신규 유저의 DJ 등록 플로우에서 "플레이리스트 생성" 단계가 사라지고 곡 추가부터 시작.
- 기존 유저·봇·기존 API 동작 회귀 0.

## 비목표 (YAGNI)

- 기존 유저 백필. `is_auto_created` 플래그. 곡 자동 채움. 삭제/rename 가드 추가. GRABLIST 관련 일체 변경. 웹 코드 변경. TEMP 플리 생성(quick-dj #3 소관 — 아래 정합 절).

## 설계 (백엔드 국소 — 스키마/마이그레이션/정책 무변경)

### ① `PlaylistCommandService.createDefaultDjPlaylist(userId)` 신설

```java
public void createDefaultDjPlaylist(UserId userId) {
    PlaylistData playlist = PlaylistData.create(1, "내 플레이리스트", PlaylistType.PLAYLIST, userId);
    aggregatePort.savePlaylist(playlist);  // 기존 createDefaultPlaylist(라인 29-33)와 동일 저장 경로
}
```

- 기존 `createDefaultPlaylist`(GRABLIST, order 0)와 동일 패턴: **정책 검사 우회**(가입 시점 시스템 생성이라 상한 무관), orderNumber 1(GRABLIST=0 다음).
- orderNumber 참고: 기존 `createPlaylist`의 orderNumber 산정(`isEmpty ? 1 : size`)은 중복 order를 이미 허용하는 선재 quirk — 본 건은 그 동작을 바꾸지 않으며, 기본 플리(order 1) 이후 첫 수동 생성이 order 1을 재사용해도 기존과 동일한 수준의 무해한 중복이다.

### ② `PlaylistSetupPort` 확장 + 사람 가입 경로 2곳 배선

- `PlaylistSetupPort`에 `createDefaultDjPlaylist(UserId)` 추가, `PlaylistSetupAdapter`(bootstrap) 위임 구현.
- 호출 지점 — 기존 `createDefaultPlaylist` 호출 **바로 다음 줄**:
  - `MemberSignService.initializeNewMember` (`MemberSignService.java:126` 다음) — 소셜 가입.
  - `TemporaryUserInitializeService` (`:113` 다음) — 게스트→회원 전환(신규 멤버 생성 `orElseGet` 내부라 기존 회원 재진입 없음 — 중복 생성 위험 없음 확인).

### ③ 봇/가상 멤버 경로 의도적 제외 (핵심 가드)

`AdminUserService.createVirtualMember`(→`adminPlaylistPort.createDefaultPlaylist`)는 **배선하지 않는다.**

- 근거: 봇은 `VirtualUserPoolService.provision`이 자체 PLAYLIST(`BOT_PLAYLIST_NAME`, order 1)를 직접 생성하며, 송팩 적용·DJ enqueue가 `playlistRepository.findByOwnerIdAndType(botUserId, PLAYLIST)` **단건 조회**(`playlistIdOf`)에 의존한다. 기본 플리를 추가하면 PLAYLIST 2개가 되어 이 조회가 파손된다.
- `AdminPlaylistPort`/`AdminPlaylistAdapter`도 무변경. 제외 근거를 신설 메서드 javadoc에 명문화하고, **회귀 IT("봇 provision 후 PLAYLIST 정확히 1개")로 고정**한다.
- 부수 효과(의도): `createVirtualMember`를 쓰는 데모/가상 멤버(`AdminPartyroomService`/`AdminDemoService`)도 기본 플리를 받지 않는다 — 실사용자 온보딩 전용 기능이므로 올바른 동작. javadoc에 한 줄 병기.

### 삭제 시맨틱 (검토 완료 — 허용)

- `deletePlaylist`는 소유권 검사만 하며 기본 플리는 무플래그 평범한 PLAYLIST 행 → 삭제 가능(의도).
- **락아웃 없음**: `PlaylistCreationPolicy`는 현존 행 수 기준 카운트 — 삭제 후 AM도 다시 1개 직접 생성 가능.
- **자동 재생성 없음**: 의도적 삭제 존중, 이후 구 플로우(직접 생성)로 자연 복귀. "기본 플리 존재"를 가정하는 코드가 백엔드·웹 어디에도 없음(웹 0-플리 분기도 존치).
- 참고(선재, 본 건 무관·무악화): 삭제에 타입 가드가 없어 GRABLIST도 API로는 삭제 가능(웹 UI가 미노출로 방어) / DJ 등록 중 플리 삭제 가드 없음.

### quick-dj(#3, 승인 스펙 2026-07-01)와의 정합

- quick-dj의 숨김 `PlaylistType.TEMP` 플리는 그 스펙의 `findOrCreateTempPlaylist`가 **첫 사용 시 lazy 생성** — 기존·신규 유저 전원을 자동 커버하므로 **본 건에서 TEMP를 만들지 않는다**(가입 초기화 세트는 GRABLIST+기본 플리로 한정).
- 접점 전수 검사(2026-07-11): PlaylistType(무추가 vs TEMP 추가·STRING 안전) / 상한(PLAYLIST만 카운트 → TEMP 자동 제외, 기본 플리는 포함) / 목록 노출(기본 플리 노출 vs TEMP 필터) / 봇(양쪽 다 제외) / `findByOwnerIdAndType` 단건 조회(봇 전용이라 사람의 PLAYLIST 복수 보유와 무관, TEMP 별개 타입) — **전부 무충돌.** 구현 순서 #2→#3 무방.

## 오류 처리

- 신설 메서드는 기존 `createDefaultPlaylist` **바로 다음 줄, 동일 트랜잭션 문맥**에서 실행 — 소셜 가입 경로(`getOrCreateMemberFor` @Transactional)는 실패 시 가입 전체 롤백. 게스트→회원 경로(`addAssociateMember`)는 선재적으로 비-@Transactional(호출별 독립 커밋)이나, 기존 GRABLIST 생성과 **정확히 동일한 시맨틱**을 공유하므로 신규 실패 모드 없음.

## 테스트

- **유닛**: `createDefaultDjPlaylist`(타입 PLAYLIST·이름 "내 플레이리스트"·order 1·정책 미호출). `MemberSignService`/`TemporaryUserInitializeService`가 두 생성 메서드를 각 1회 호출(기존 "createDefaultPlaylist 1회" 단언 테스트 갱신).
- **IT**: ①가입 초기화 → GRABLIST 1 + PLAYLIST("내 플레이리스트") 1 단언 ②**봇 provision → PLAYLIST 타입 정확히 1개(BOT_PLAYLIST_NAME) 단언 — 이중 생성 회귀 방지 핵심** ③AM 유저(기본 플리 보유)가 `createPlaylist` 시도 → `PLL-002 EXCEEDED_PLAYLIST_LIMIT` ④기본 플리 삭제 후 `createPlaylist` → 성공(락아웃 없음).
- **로컬 검증 게이트**: fresh DB docker 부팅(bootJar 선행) + 라이브 확인(신규 가입 경로 → 플리 목록에 "내 플레이리스트") + 웹 dj-register e2e 회귀(기존 `createPlaylistWithTracks` 헬퍼 플로우 무영향 확인).
- 기존 전체 유닛/IT 회귀 그린.

## 산출물 / 절차

- 이슈: platform **#329**. 브랜치: `feat/default-dj-playlist-329`(origin/develop 분기). 웹/어드민 무변경 — 단일 레포 PR.
- 커밋/PR 한글, dev 머지 전 로컬 풀스택 게이트, 머지=사용자 게이트.
